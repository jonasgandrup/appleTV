package app.lumen.core.apps

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.tv.TvContract.PreviewPrograms
import android.media.tv.TvContract.WatchNextPrograms
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import kotlin.math.max

/** What an app publishes for the stage behind the dock: artwork, and what to watch next. */
class ShelfArt(
    val packageName: String,
    val title: String?,
    /** 16:9, already cropped and downscaled to [TopShelfRepository.ART_WIDTH] wide. */
    val image: Bitmap,
    /** The app's Watch Next row, most recently watched first. Empty when it has none. */
    val upNext: List<UpNextItem>,
)

/** A program in an app's Watch Next row. */
class UpNextItem(
    val packageName: String,
    val title: String,
    /** Tile art at [TopShelfRepository.THUMB_WIDTH] by [TopShelfRepository.THUMB_HEIGHT], with the progress bar drawn in. */
    val thumbnail: Bitmap,
    /** Opens the program in its app; null when the app gave none. */
    val intentUri: String?,
)

/**
 * Reads the artwork apps publish to the system TV provider, like tvOS's Top Shelf.
 *
 * The provider normally limits a caller to its own rows, but a query from an app holding
 * READ_TV_LISTINGS also returns every other app's searchable rows. The backdrop comes from
 * Watch Next first (what the user is in the middle of), then the app's preview channels.
 * Results, including "nothing there", are cached for a while, since apps only refresh their
 * channels now and then.
 */
class TopShelfRepository(context: Context) {

    private val context = context.applicationContext
    private val cache = ConcurrentHashMap<String, Entry>()

    private class Entry(val art: ShelfArt?, val loadedAt: Long)

    val hasPermission: Boolean
        get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The cached art for [packageName] without loading anything, or null. Safe on the main thread. */
    fun peek(packageName: String): ShelfArt? = cache[packageName]?.art

    suspend fun load(packageName: String): ShelfArt? {
        cache[packageName]?.takeIf { SystemClock.elapsedRealtime() - it.loadedAt < CACHE_MS }?.let { return it.art }
        if (!hasPermission) return null
        return withContext(Dispatchers.IO) {
            val art = runCatching { query(packageName) }
                .onFailure { Log.w(TAG, "No shelf art for $packageName", it) }
                .getOrNull()
            cache[packageName] = Entry(art, SystemClock.elapsedRealtime())
            art
        }
    }

    private suspend fun query(packageName: String): ShelfArt? = coroutineScope {
        val watchNext = programs(WATCH_NEXT_URI, packageName, LAST_ENGAGEMENT, MAX_UP_NEXT)
        val preview = programs(PREVIEW_URI, packageName, WEIGHT, MAX_CANDIDATES)
        // The backdrop is usually the first Watch Next tile too, so each image is fetched once.
        val downloads = HashMap<Uri, Deferred<ByteArray?>>()
        fun download(uri: Uri) = downloads.getOrPut(uri) {
            async(Dispatchers.IO) { runCatching { open(uri).use(InputStream::readBytes) }.getOrNull() }
        }
        (watchNext + preview).forEach { download(it.imageUri) }

        val upNext = watchNext.map { program ->
            async(Dispatchers.Default) {
                val title = program.title?.takeIf(String::isNotBlank) ?: return@async null
                val bytes = download(program.imageUri).await() ?: return@async null
                val thumbnail = decode(bytes, THUMB_WIDTH, THUMB_HEIGHT, MIN_THUMB_WIDTH) ?: return@async null
                program.progress?.let { drawProgress(thumbnail, it) }
                UpNextItem(packageName, title, thumbnail, program.intentUri)
            }
        }
        var backdrop: Pair<Program, Bitmap>? = null
        for (program in watchNext.take(MAX_CANDIDATES) + preview) {
            val bytes = download(program.imageUri).await() ?: continue
            val image = decode(bytes, ART_WIDTH, ART_HEIGHT, MIN_WIDTH) ?: continue
            backdrop = program to image
            break
        }
        val items = upNext.awaitAll().filterNotNull()
        backdrop?.let { (program, image) -> ShelfArt(packageName, program.title, image, items) }
    }

    private class Program(
        val title: String?,
        val imageUri: Uri,
        val rank: Long,
        val intentUri: String?,
        /** 0..1 through the program, or null if it hasn't been started. */
        val progress: Float?,
    )

    /** Up to [limit] programs with landscape art, best first: most recently watched, or highest weighted. */
    private fun programs(base: Uri, packageName: String, rankColumn: String, limit: Int): List<Program> {
        // The provider rejects selections from apps without full EPG access; it filters by this parameter instead.
        val uri = base.buildUpon().appendQueryParameter("package", packageName).build()
        val projection = arrayOf(
            TITLE, POSTER_URI, POSTER_ASPECT, THUMBNAIL_URI, THUMBNAIL_ASPECT, INTENT_URI, POSITION, DURATION, rankColumn,
        )
        val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val image = landscapeArt(c) ?: continue
                    val position = c.longOrZero(POSITION)
                    val duration = c.longOrZero(DURATION)
                    val progress = if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null
                    add(Program(c.stringOrNull(TITLE), image, c.longOrZero(rankColumn), c.stringOrNull(INTENT_URI), progress))
                }
            }.sortedByDescending { it.rank }.take(limit)
        }
    }

    /** The poster if it's landscape, else the thumbnail if that is. Portrait art can't fill a stage. */
    private fun landscapeArt(c: Cursor): Uri? {
        fun art(uriColumn: String, aspectColumn: String): Uri? {
            val uri = c.stringOrNull(uriColumn)?.takeIf { it.isNotBlank() } ?: return null
            val aspect = c.getColumnIndex(aspectColumn).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getInt)
            return Uri.parse(uri).takeIf { aspect == null || aspect in LANDSCAPE_ASPECTS }
        }
        return art(POSTER_URI, POSTER_ASPECT) ?: art(THUMBNAIL_URI, THUMBNAIL_ASPECT)
    }

    /**
     * Decodes [bytes] at about [width] by [height] and center-crops it to that shape. Null when the
     * image is narrower than [minWidth] or not landscape.
     */
    private fun decode(bytes: ByteArray, width: Int, height: Int, minWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < minWidth || bounds.outHeight <= 0 || bounds.outWidth < bounds.outHeight) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = max(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
        val cropW = (width / scale).toInt()
        val cropH = (height / scale).toInt()
        val left = (decoded.width - cropW) / 2
        // Keep the top of tall art, where faces and titles usually are.
        val top = if (decoded.height > cropH) ((decoded.height - cropH) * 0.2f).toInt() else 0
        Canvas(out).drawBitmap(
            decoded,
            Rect(left, top, left + cropW, top + cropH),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        decoded.recycle()
        out.setHasAlpha(false)
        return out
    }

    /** The largest power-of-two subsample that still leaves the image at least [width] by [height]. */
    private fun sampleSize(imageWidth: Int, imageHeight: Int, width: Int, height: Int): Int {
        var sample = 1
        while (imageWidth / (sample * 2) >= width && imageHeight / (sample * 2) >= height) sample *= 2
        return sample
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

        /**
         * Stage art size. Behind scrims it holds up stretched to 1080p, and it's small enough for the
         * Chromecast to upload in a frame and draw full screen cheaply.
         */
        const val ART_WIDTH = 960
        const val ART_HEIGHT = 540

        /** Up Next tiles share the dock's tile shape; this is a little over their focused size in pixels. */
        const val THUMB_WIDTH = 320
        const val THUMB_HEIGHT = 192

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
        private const val LAST_ENGAGEMENT = WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        private const val WEIGHT = PreviewPrograms.COLUMN_WEIGHT
        /** 16:9, 3:2 and 4:3. Square and portrait art would be cropped to a sliver. */
        private val LANDSCAPE_ASPECTS = setOf(
            PreviewPrograms.ASPECT_RATIO_16_9,
            PreviewPrograms.ASPECT_RATIO_3_2,
            PreviewPrograms.ASPECT_RATIO_4_3,
        )
        private const val MIN_WIDTH = 480
        private const val MIN_THUMB_WIDTH = 200
        private const val MAX_CANDIDATES = 3
        private const val MAX_UP_NEXT = 8
        private const val PROGRESS_INSET = 0.06f
        private const val PROGRESS_HEIGHT = 0.03f
        /** A sliver of fill even at the very start, so the bar reads as progress rather than a line. */
        private const val PROGRESS_MIN = 0.04f
        private const val NETWORK_TIMEOUT_MS = 4_000
        private const val CACHE_MS = 15 * 60_000L
    }
}
