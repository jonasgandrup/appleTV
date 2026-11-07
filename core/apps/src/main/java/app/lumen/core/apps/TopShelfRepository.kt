package app.lumen.core.apps

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.tv.TvContract.PreviewPrograms
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * What an app shows above the top row, as a Top Shelf's row of tiles does on tvOS: what the user is in
 * the middle of in it, then what it recommends.
 */
class ShelfArt(
    val packageName: String,
    val recommendations: List<UpNextItem>,
    /** "Up Next" when the row starts with what the user is watching, else "Recommended". */
    val heading: String,
    /** Differs whenever anything shown here does, so a reread that changed nothing leaves the screen alone. */
    internal val signature: String,
)

/** A program shown as a tile, in Continue Watching or in an app's recommendations. */
class UpNextItem(
    val packageName: String,
    val title: String,
    /**
     * Tile art with the progress bar drawn in: 16:9, sized for the row it's shown in. Kept on the graphics
     * chip where it can be, so its pixels can't be read; use [glow] for that.
     */
    val thumbnail: Bitmap,
    /** A small copy of [thumbnail] whose pixels can be read, for the stage's colors. */
    val glow: Bitmap,
    /** Where the user is, like "S3, E4 · 23 min left", leaving out whatever the app didn't say. */
    val detail: String?,
    /** Opens the program in its app; null when the app gave none. */
    val intentUri: String?,
    /** Where the user is in it. When this changes, they've watched it. */
    internal val place: String,
)

/**
 * Reads what apps publish to the system TV provider: each app's row above the top row, like tvOS's
 * Top Shelf, and Continue Watching across them.
 *
 * The provider normally limits a caller to its own rows, but a query from an app holding
 * READ_TV_LISTINGS also returns every other app's searchable rows. An app's row comes from its
 * Watch Next first (what the user is in the middle of), then its preview channels.
 * Results, including "nothing there", are cached for a while so moving along the dock stays quick,
 * and reread when the user comes back from an app, which is when they're likely to have changed.
 * A read that fails never replaces a good result: only what the app actually published does.
 */
class TopShelfRepository(context: Context) {

    private val context = context.applicationContext
    private val cache = ConcurrentHashMap<String, Entry>()
    /** Replaced whole, by [continueWatching], which runs one at a time, and by [removeProgram]. */
    @Volatile
    private var continuing: List<UpNextItem> = emptyList()
    private var continuingSignature: String? = null

    /** Programs the user removed, and where they were in each then. See [removeProgram]. */
    @Volatile
    var removedPrograms: Map<String, String> = emptyMap()

    private class Entry(val art: ShelfArt?, val staleAt: Long, val complete: Boolean)

    /** What a read found, and whether everything it asked for arrived. */
    private class Loaded(val art: ShelfArt?, val complete: Boolean)

    val hasPermission: Boolean
        get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The cached art for [packageName] without loading anything, or null. Safe on the main thread. */
    fun peek(packageName: String): ShelfArt? = cache[packageName]?.art

    suspend fun load(packageName: String): ShelfArt? {
        cache[packageName]?.takeIf { SystemClock.elapsedRealtime() < it.staleAt }?.let { return it.art }
        return refresh(packageName)
    }

    /** Reads [packageName]'s art again whatever the cache says. Hands back what was already there if nothing changed, or if the read failed. */
    suspend fun refresh(packageName: String): ShelfArt? {
        if (!hasPermission) return null
        return withContext(Dispatchers.IO) {
            val loaded = try {
                query(packageName)
            } catch (e: CancellationException) {
                // Focus moved on. That says nothing about the app, so nothing gets remembered.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No shelf art for $packageName", e)
                null
            }
            val previous = cache[packageName]?.art
            val art = when {
                loaded == null -> previous
                previous != null && !loaded.complete && loaded.art.isWorseThan(previous) -> previous
                loaded.art?.signature == previous?.signature -> previous
                else -> loaded.art
            }
            val complete = loaded?.complete == true
            cache[packageName] = Entry(art, SystemClock.elapsedRealtime() + if (complete) CACHE_MS else RETRY_MS, complete)
            art
        }
    }

    /** True when the last read of [packageName] came up short, so another try is worthwhile. */
    fun isIncomplete(packageName: String): Boolean = cache[packageName]?.complete == false

    /** Everything is reread the next time it's asked for, while still showing what's known until then. */
    fun expireAll() {
        cache.replaceAll { _, entry -> Entry(entry.art, 0L, entry.complete) }
    }

    /**
     * What the user is in the middle of across all of [packageNames], most recently watched first.
     * Programs an app merely suggests or has on a watchlist stay out. Anything that didn't change
     * since last time isn't fetched again.
     */
    suspend fun continueWatching(packageNames: List<String>): List<UpNextItem> {
        if (!hasPermission) return emptyList()
        return withContext(Dispatchers.IO) {
            val programs = packageNames.flatMap { packageName ->
                val watchNext = runCatching { programs(WATCH_NEXT_URI, packageName, LAST_ENGAGEMENT, MAX_UP_NEXT) }.getOrDefault(emptyList())
                if (watchNext.isNotEmpty()) Log.d(TAG, "$packageName Watch Next types: ${watchNext.map { it.watchNextType }}")
                watchNext.filter { it.isContinuing }.map { packageName to it }
            }.sortedByDescending { (_, program) -> program.rank }.take(MAX_CONTINUING)
            val signature = programs.joinToString("|") { (packageName, it) -> "$packageName${it.title}${it.detail}${it.progress}${it.intentUri}" }
            if (signature == continuingSignature) return@withContext continuing
            coroutineScope {
                val items = programs.map { (packageName, program) ->
                    async {
                        val bytes = runCatching { open(program.art.first()).use(InputStream::readBytes) }.getOrNull() ?: return@async null
                        upNextItem(packageName, program, bytes)
                    }
                }.awaitAll().filterNotNull()
                // A download that failed gets another try next time, with the fuller row staying up until then.
                if (items.size < continuing.size && items.size < programs.size) return@coroutineScope continuing
                if (items.size == programs.size) continuingSignature = signature
                continuing = items
                items
            }
        }
    }

    /**
     * Hides [item] from Continue Watching, the stage and full screen. Apps' own lists can't be changed
     * from here, so it comes back only once where the user is in it changes, meaning they've watched
     * it. Returns what's left of Continue Watching; the stage catches up on its next read.
     */
    fun removeProgram(item: UpNextItem): List<UpNextItem> {
        val key = programKey(item.packageName, item.title)
        // Taken out first, so the newest removal goes last and is the last to be forgotten.
        removedPrograms = removedPrograms - key + (key to item.place)
        cache.computeIfPresent(item.packageName) { _, entry -> Entry(entry.art, 0L, entry.complete) }
        continuing = continuing.filterNot { it.packageName == item.packageName && it.title == item.title }
        return continuing
    }

    private fun programKey(packageName: String, title: String?) = "$packageName|$title"

    /** Whether a read that came up short lost something, rather than the app having dropped it. */
    private fun ShelfArt?.isWorseThan(previous: ShelfArt): Boolean =
        this == null || recommendations.size < previous.recommendations.size

    private suspend fun query(packageName: String): Loaded = coroutineScope {
        val watchNext = programs(WATCH_NEXT_URI, packageName, LAST_ENGAGEMENT, MAX_UP_NEXT)
        val preview = programs(PREVIEW_URI, packageName, WEIGHT, MAX_ROW)
        Log.d(TAG, "$packageName publishes ${watchNext.size} Watch Next and ${preview.size} preview programs")
        // A program's poster and thumbnail can be the same image, so each is fetched once.
        val downloads = HashMap<Uri, Deferred<ByteArray?>>()
        fun download(uri: Uri) = downloads.getOrPut(uri) {
            async(Dispatchers.IO) { runCatching { open(uri).use(InputStream::readBytes) }.getOrNull() }
        }
        // What the user is in the middle of first, as tvOS's Up Next does, then what the app suggests.
        val programs = (watchNext + preview).distinctBy { it.title }.take(MAX_ROW)
        val recommendations = programs.map { program ->
            val art = program.art.map(::download)
            async(Dispatchers.Default) {
                // The sharper of its poster and thumbnail, as the tile is large.
                val bytes = art.awaitAll().filterNotNull().maxByOrNull { landscapeWidth(it) ?: 0 }
                bytes?.let { upNextItem(packageName, program, it, large = true) }
            }
        }.awaitAll().filterNotNull()
        val complete = downloads.values.awaitAll().none { it == null }
        val signature = recommendations.joinToString("|") { it.title + it.detail + it.intentUri }
        val heading = if (watchNext.any { program -> recommendations.any { it.title == program.title } }) "Up Next" else "Recommended"
        Loaded(recommendations.takeIf { it.isNotEmpty() }?.let { ShelfArt(packageName, it, heading, signature) }, complete)
    }

    /** The tile for [program], or null if it has no title or its art is too small to use. */
    private fun upNextItem(packageName: String, program: Program, art: ByteArray, large: Boolean = false): UpNextItem? {
        val title = program.title?.takeIf(String::isNotBlank) ?: return null
        val thumbnail = if (large) {
            decodeArt(art, LARGE_THUMB_WIDTH, LARGE_THUMB_HEIGHT, upscale = false, minWidth = MIN_THUMB_WIDTH)
        } else {
            decodeArt(art, THUMB_WIDTH, THUMB_HEIGHT, upscale = true, minWidth = MIN_THUMB_WIDTH)
        } ?: return null
        program.progress?.let { drawProgress(thumbnail, it) }
        val glow = Bitmap.createScaledBitmap(thumbnail, GLOW_WIDTH, GLOW_HEIGHT, true)
        return UpNextItem(packageName, title, thumbnail.onGraphicsChip(), glow, program.detail, program.intentUri, program.place)
    }

    private class Program(
        val title: String?,
        /** Its landscape art, poster first, then thumbnail. Never empty. */
        val art: List<Uri>,
        val rank: Long,
        val intentUri: String?,
        /** 0..1 through the program, or null if it hasn't been started. */
        val progress: Float?,
        val detail: String?,
        /** Why it's in Watch Next; null for a preview program, or when the app didn't say. */
        val watchNextType: Int?,
        val place: String,
    ) {
        /** Started, or the next episode of something that was. A program with no stated type counts only if it's been started. */
        val isContinuing: Boolean
            get() = when (watchNextType) {
                WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, WatchNextPrograms.WATCH_NEXT_TYPE_NEXT -> true
                null -> progress != null
                else -> false
            }
    }

    /** Up to [limit] programs with landscape art, best first: most recently watched, or highest weighted. */
    private fun programs(base: Uri, packageName: String, rankColumn: String, limit: Int): List<Program> {
        // The provider rejects selections from apps without full EPG access; it filters by this parameter instead.
        val uri = base.buildUpon().appendQueryParameter("package", packageName).build()
        val isWatchNext = base == WATCH_NEXT_URI
        val projection = arrayOf(
            TITLE, POSTER_URI, POSTER_ASPECT, THUMBNAIL_URI, THUMBNAIL_ASPECT, INTENT_URI, POSITION, DURATION,
            SEASON, EPISODE, rankColumn,
        ) + if (isWatchNext) arrayOf(WATCH_NEXT_TYPE) else emptyArray()
        val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val art = landscapeArt(c)
                    if (art.isEmpty()) continue
                    val position = c.longOrZero(POSITION)
                    val duration = c.longOrZero(DURATION)
                    val progress = if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null
                    val episode = episodeLabel(c.stringOrNull(SEASON), c.stringOrNull(EPISODE))
                    // How much is left once started, or how long it is before then.
                    val time = timeLeft(position, duration) ?: duration.takeIf { position <= 0 }?.let(::lengthLabel)
                    val detail = listOfNotNull(episode, time).joinToString(" · ").ifEmpty { null }
                    val type = if (isWatchNext) c.getColumnIndex(WATCH_NEXT_TYPE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getInt) else null
                    val title = c.stringOrNull(TITLE)
                    val place = "$episode@$position"
                    if (isWatchNext && removedPrograms[programKey(packageName, title)] == place) continue
                    add(Program(title, art, c.longOrZero(rankColumn), c.stringOrNull(INTENT_URI), progress, detail, type, place))
                }
            }.sortedByDescending { it.rank }.take(limit)
        }
    }

    /** "S3, E4" as Apple TV writes it, or whichever half the app gave. Display numbers may be words. */
    private fun episodeLabel(season: String?, episode: String?): String? {
        fun part(prefix: String, value: String?) = value?.trim()?.takeIf(String::isNotEmpty)?.let { if (it.all(Char::isDigit)) "$prefix$it" else it }
        return listOfNotNull(part("S", season), part("E", episode)).joinToString(", ").ifEmpty { null }
    }

    /** "23 min left", "1 hr 5 min left"; null for a program not started or already finished. */
    private fun timeLeft(position: Long, duration: Long): String? {
        if (position <= 0 || duration <= position) return null
        return "${lengthLabel(duration - position)} left"
    }

    /** "23 min", "2 hr", "1 hr 5 min"; null when there's no length to tell. */
    private fun lengthLabel(ms: Long): String? {
        if (ms <= 0) return null
        val minutes = ((ms + 59_999) / 60_000).toInt()
        val hours = minutes / 60
        return when {
            hours == 0 -> "$minutes min"
            minutes % 60 == 0 -> "$hours hr"
            else -> "$hours hr ${minutes % 60} min"
        }
    }

    /** The poster and thumbnail, each if it's landscape. Portrait art would be cropped to a sliver in a wide tile. */
    private fun landscapeArt(c: Cursor): List<Uri> {
        fun art(uriColumn: String, aspectColumn: String): Uri? {
            val uri = c.stringOrNull(uriColumn)?.takeIf { it.isNotBlank() } ?: return null
            val aspect = c.getColumnIndex(aspectColumn).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getInt)
            return Uri.parse(uri).takeIf { aspect == null || aspect in LANDSCAPE_ASPECTS }
        }
        return listOfNotNull(art(POSTER_URI, POSTER_ASPECT), art(THUMBNAIL_URI, THUMBNAIL_ASPECT)).distinct()
    }

    /** The image's width if it's landscape, else null. Reads only its header. */
    private fun landscapeWidth(bytes: ByteArray): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth.takeIf { it > 0 && it >= bounds.outHeight }
    }

    /**
     * This bitmap moved to the graphics chip, where it's drawn from, rather than kept in ordinary memory and
     * copied there as well: a row of large tiles is over 10 MB in each place. Falls back to this one when
     * the chip won't take it.
     */
    private fun Bitmap.onGraphicsChip(): Bitmap {
        val moved = runCatching { copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return this
        recycle()
        return moved
    }

    /** tvOS's thin progress bar along the bottom of the tile: a white fill on a translucent track. */
    private fun drawProgress(thumbnail: Bitmap, progress: Float) {
        val canvas = Canvas(thumbnail)
        val w = thumbnail.width.toFloat()
        val h = thumbnail.height.toFloat()
        val inset = w * PROGRESS_INSET
        val bar = h * PROGRESS_HEIGHT
        val bottom = h - inset
        val track = RectF(inset, bottom - bar, w - inset, bottom)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A soft shade under the bar keeps it visible on bright art.
        paint.color = 0x59000000
        canvas.drawRoundRect(RectF(track.left - 1, track.top - 1, track.right + 1, track.bottom + 1), bar, bar, paint)
        paint.color = 0x66FFFFFF
        canvas.drawRoundRect(track, bar / 2, bar / 2, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val fill = RectF(track.left, track.top, track.left + track.width() * progress.coerceAtLeast(PROGRESS_MIN), track.bottom)
        canvas.drawRoundRect(fill, bar / 2, bar / 2, paint)
    }

    private fun open(uri: Uri): InputStream = when (uri.scheme) {
        "http", "https" -> (URL(uri.toString()).openConnection() as HttpURLConnection).run {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            inputStream.buffered()
        }
        else -> context.contentResolver.openInputStream(uri) ?: error("Can't open $uri")
    }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.longOrZero(column: String): Long =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

    companion object {
        /** Not in the public SDK's Manifest.permission, but an ordinary runtime permission. */
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"

        /** Up Next tiles are 16:9, unlike the dock's; this is their focused size in pixels. */
        const val THUMB_WIDTH = 448
        const val THUMB_HEIGHT = 252

        /** The most a tile in an app's own row decodes, its size on screen; smaller art keeps its own size. */
        private const val LARGE_THUMB_WIDTH = 782
        private const val LARGE_THUMB_HEIGHT = 440

        private const val TAG = "TopShelf"
        private val WATCH_NEXT_URI = WatchNextPrograms.CONTENT_URI
        private val PREVIEW_URI = PreviewPrograms.CONTENT_URI
        private const val TITLE = PreviewPrograms.COLUMN_TITLE
        private const val POSTER_URI = PreviewPrograms.COLUMN_POSTER_ART_URI
        private const val POSTER_ASPECT = PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO
        private const val THUMBNAIL_URI = PreviewPrograms.COLUMN_THUMBNAIL_URI
        private const val THUMBNAIL_ASPECT = PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO
        private const val INTENT_URI = PreviewPrograms.COLUMN_INTENT_URI
        private const val POSITION = PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS
        private const val DURATION = PreviewPrograms.COLUMN_DURATION_MILLIS
        private const val SEASON = PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER
        private const val EPISODE = PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER
        private const val LAST_ENGAGEMENT = WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        private const val WATCH_NEXT_TYPE = WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE
        private const val WEIGHT = PreviewPrograms.COLUMN_WEIGHT
        /** 16:9, 3:2 and 4:3. Square and portrait art would be cropped to a sliver. */
        private val LANDSCAPE_ASPECTS = setOf(
            PreviewPrograms.ASPECT_RATIO_16_9,
            PreviewPrograms.ASPECT_RATIO_3_2,
            PreviewPrograms.ASPECT_RATIO_4_3,
        )
        private const val MIN_THUMB_WIDTH = 200
        private const val MAX_UP_NEXT = 8
        /** About what fits across the screen twice, as tvOS's rows hold. */
        private const val MAX_ROW = 10
        /** Plenty for a picture that's only ever blurred into the stage's colors. */
        private const val GLOW_WIDTH = 192
        private const val GLOW_HEIGHT = 108
        private const val MAX_CONTINUING = 12
        private const val PROGRESS_INSET = 0.05f
        private const val PROGRESS_HEIGHT = 0.024f
        /** A sliver of fill even at the very start, so the bar reads as progress rather than a line. */
        private const val PROGRESS_MIN = 0.04f
        private const val NETWORK_TIMEOUT_MS = 4_000
        private const val CACHE_MS = 15 * 60_000L
        private const val RETRY_MS = 30_000L
    }
}
