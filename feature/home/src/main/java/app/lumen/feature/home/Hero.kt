package app.lumen.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lumen.core.apps.AppCategory
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.designsystem.LiveFrost
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.WallpaperBackground
import app.lumen.core.designsystem.clearFrosted
import app.lumen.core.designsystem.renderGradient
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What's on the stage, at the top left where tvOS puts a show's logo: the title of the app's
 * featured program, or the app's own name when it publishes none. It fades through an offscreen
 * layer, so that layer is kept to the size of the text.
 *
 * When the app has a Watch Next row, that sits at the foot of the stage, just above the dock, as
 * Continue Watching does in Apple's TV app.
 */
@Composable
internal fun Hero(app: AppInfo?, shelfArt: ShelfArt?, visible: Boolean, onRowFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 450 else 200), label = "heroAlpha")
    Box(modifier) {
        AnimatedContent(
            targetState = app?.let { HeroText(it, shelfArt?.title?.takeIf(String::isNotBlank)) },
            contentKey = { it?.app?.packageName to it?.programTitle },
            transitionSpec = {
                (fadeIn(tween(420, delayMillis = 60)) + slideInHorizontally(tween(420)) { it / 30 }) togetherWith
                    fadeOut(tween(180)) using null
            },
            contentAlignment = Alignment.TopStart,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = LumenDimens.SafeHorizontal, top = LumenDimens.SafeVertical)
                .widthIn(max = HeroTitleMaxWidth)
                .graphicsLayer { this.alpha = alpha },
            label = "hero",
        ) { target ->
            if (target != null) {
                val title = target.programTitle ?: target.app.label
                val subtitle = target.app.category.takeIf { target.programTitle == null && it != AppCategory.Other }?.displayName
                Column {
                    LumenText(title, if (title.length <= HERO_DISPLAY_LENGTH) LumenType.Display else LumenType.Title, maxLines = 2)
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        LumenText(subtitle, LumenType.Section, color = LumenColors.TextSecondary, maxLines = 1)
                    }
                }
            }
        }
        // Keyed by app, so one app's row fades out as the next one's fades in, and gone entirely
        // while browsing below, so focus can't land in it from the grid.
        AnimatedContent(
            targetState = shelfArt?.takeIf { visible && it.upNext.isNotEmpty() },
            contentKey = { it?.packageName },
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) using null },
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            label = "upNext",
        ) { target ->
            // Down into the space the list leaves above the dock's glass, so the row sits right over it.
            if (target != null) {
                UpNextRow(
                    items = target.upNext,
                    heading = if (target.isRecommendations) "Recommended" else "Up Next",
                    hint = "Press Up for full screen".takeIf { target.featured.isNotEmpty() },
                    modifier = Modifier.offset(y = LumenDimens.SafeVertical - 6.dp).onFocusChanged { onRowFocus(it.hasFocus) },
                )
            }
        }
    }
}

private data class HeroText(val app: AppInfo, val programTitle: String?)

/** Titles longer than this drop a size, so a long one doesn't fill the stage. */
private const val HERO_DISPLAY_LENGTH = 24
private val HeroTitleMaxWidth = 560.dp

/**
 * The [hero]'s own artwork from its TV channels, once focus has rested on it for a moment, so
 * gliding along the dock doesn't fetch and bake art for every tile passed. Art already loaded
 * shows at once. Holds the last art until the next arrives; check its package before using it.
 *
 * What's shown stays until something better replaces it. Coming back from an app rereads it, and
 * again a little later, since apps can take a moment to publish what was just watched.
 */
@Composable
internal fun rememberShelfArt(hero: State<AppInfo?>, enabled: Boolean, viewModel: HomeViewModel): State<ShelfArt?> {
    val art = remember { mutableStateOf<ShelfArt?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled) {
        snapshotFlow { hero.value?.takeIf { enabled } }
            .distinctUntilChangedBy { it?.packageName }
            .collectLatest { app ->
                art.value = app?.let(viewModel::cachedShelfArt)
                if (app == null) return@collectLatest
                delay(SHELF_ART_SETTLE_MS)
                art.value = viewModel.shelfArt(app)
                var retries = SHELF_ART_RETRIES
                while (retries-- > 0 && viewModel.shelfArtIncomplete(app)) {
                    delay(SHELF_ART_RETRY_MS)
                    art.value = viewModel.refreshShelfArt(app)
                }
                lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.RESUMED) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest { resumed ->
                        if (!resumed) return@collectLatest
                        viewModel.expireShelfArt()
                        art.value = viewModel.refreshShelfArt(app)
                        delay(SHELF_ART_RECHECK_MS)
                        art.value = viewModel.refreshShelfArt(app)
                    }
            }
    }
    return art
}

private const val SHELF_ART_SETTLE_MS = 250L
private const val SHELF_ART_RETRIES = 3
private const val SHELF_ART_RETRY_MS = 10_000L
private const val SHELF_ART_RECHECK_MS = 20_000L

/**
 * The full-screen stage behind everything: the focused app's [shelfArt] when it publishes some,
 * otherwise the wallpaper lit in the app's color, plus scrims that keep text legible. While
 * browsing [below] the Top Shelf it melts into a frosted copy of itself, as tvOS's does.
 *
 * All of it is baked into one small bitmap per app, off the main thread. Every extra full-screen
 * layer is another pass for the Chromecast's GPU: drawn separately, these held it to 20 fps.
 * A photo wallpaper is too detailed to bake that small, so it stays underneath, untinted.
 *
 * Also keeps [frost] matched to the stage, so the glass above it frosts what's really behind it.
 */
@Composable
internal fun Backdrop(
    wallpaper: Wallpaper,
    app: AppInfo?,
    shelfArt: ShelfArt?,
    /** A program from the full-screen Top Shelf, which replaces [shelfArt]'s artwork. */
    program: ShelfProgram?,
    loadProgram: suspend (ShelfProgram) -> Bitmap?,
    below: Boolean,
    frost: LiveFrost,
) {
    val belowAmount by animateFloatAsState(if (below) 1f else 0f, tween(450), label = "below")
    val preset = (wallpaper as? Wallpaper.Gradient)?.preset
    val art = app?.art
    val artwork = shelfArt?.takeIf { it.packageName == app?.packageName }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    // True while an opaque bake hides a photo wallpaper completely, so it needn't be drawn.
    var coversWallpaper by remember { mutableStateOf(false) }
    LaunchedEffect(preset, art, artwork, program) {
        val first = current == null
        // Quick presses along the dock restart this effect before anything is baked or faded.
        if (!first) delay(BACKDROP_SETTLE_MS)
        val (next, nextFrost) = withContext(Dispatchers.Default) {
            val picture = program?.let { loadProgram(it) } ?: artwork?.decodeBackdrop()
            val baked = picture?.let { bakeArtwork(it, hasRow = program == null && artwork?.upNext?.isNotEmpty() == true) } ?: bakeBackdrop(preset, art)
            // A transparent bake has nothing to frost by itself; glass falls back to the wallpaper's frost.
            baked.asImageBitmap() to baked.takeUnless { it.hasAlpha() }?.clearFrosted()?.asImageBitmap()
        }
        val opaque = !next.hasAlpha
        coversWallpaper = coversWallpaper && opaque
        previous = current
        current = next
        frost.previous = frost.image
        frost.image = nextFrost
        frost.fade = if (first) 1f else 0f
        if (!first) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(BACKDROP_FADE_MS)) { frost.fade = value }
        }
        // One full-screen image per frame from here on.
        previous = null
        frost.previous = null
        coversWallpaper = opaque
    }

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        if (wallpaper is Wallpaper.Image && !coversWallpaper) WallpaperBackground(wallpaper, Modifier.fillMaxSize())
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val stretched = IntSize(size.width.roundToInt(), size.height.roundToInt())
                val frosted = frost.image
                // Once fully frosted the sharp stage is hidden, so the GPU is spared it.
                if (belowAmount < 1f || frosted == null) {
                    if (fade.value < 1f) {
                        // An opaque bake can stay solid underneath; under a transparent one it has to fade out.
                        previous?.let { drawImage(it, dstSize = stretched, alpha = if (current?.hasAlpha == false) 1f else 1f - fade.value) }
                    }
                    current?.let { drawImage(it, dstSize = stretched, alpha = fade.value) }
                }
                if (belowAmount > 0f) frosted?.let { drawImage(it, dstSize = stretched, alpha = belowAmount) }
            },
        )
    }
}

/**
 * The app's artwork, full bleed, under scrims that are heavier than the color stage's: a photo
 * is busier than a gradient behind the title and the dock. With a program row above the dock
 * ([hasRow]) it fades to near black behind the row, as the art does above Continue Watching in
 * Apple's TV app. Drawn onto [bitmap] itself, which is freshly decoded for this, so a full-screen
 * bitmap is never copied. Opaque. Blocking.
 */
private fun bakeArtwork(bitmap: Bitmap, hasRow: Boolean): Bitmap {
    val canvas = Canvas(bitmap)
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val scrim = Paint().apply { isDither = true }
    scrim.shader = if (hasRow) {
        LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(0x59000000, 0x00000000, 0xC7000000.toInt(), 0xEB000000.toInt()),
            floatArrayOf(0f, 0.12f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
    } else {
        LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(0x59000000, 0x00000000, 0x1A000000, 0xB3000000.toInt()),
            floatArrayOf(0f, 0.35f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width, height, scrim)
    scrim.shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(0x99000000.toInt(), 0x00000000),
        floatArrayOf(0f, 0.6f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width, height, scrim)
    // A pool of shade behind the title, which bright art (a white set, say) would otherwise swallow.
    scrim.shader = RadialGradient(width * 0.12f, height * 0.12f, width * 0.45f, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, scrim)
    bitmap.setHasAlpha(false)
    return bitmap
}

/** Opaque over [preset]; transparent over a photo wallpaper when there's no preset. Blocking. */
private fun bakeBackdrop(preset: GradientPreset?, art: Bitmap?): Bitmap {
    val bitmap = preset?.let(::renderGradient)
        ?: Bitmap.createBitmap(PHOTO_OVERLAY_WIDTH, PHOTO_OVERLAY_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    if (preset != null && art != null) drawAppLight(canvas, art, width, height)
    // A darkening toward the bottom and the left, where the text and tiles sit.
    val scrim = Paint().apply { isDither = true }
    scrim.shader = LinearGradient(
        0f, 0f, 0f, height,
        intArrayOf(0x00000000, 0x0D000000, 0x66000000),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width, height, scrim)
    scrim.shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(0x40000000, 0x00000000),
        floatArrayOf(0f, 0.5f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width, height, scrim)
    if (preset != null) bitmap.setHasAlpha(false)
    return bitmap
}

/**
 * Fills the stage with the app's own color: Netflix goes red, Prime Video blue. This stands in for
 * tvOS's full-screen Top Shelf art when the app publishes no channel artwork, or until it loads.
 * Black and white brands have no color to offer and leave the wallpaper showing.
 */
private fun drawAppLight(canvas: Canvas, art: Bitmap, width: Float, height: Float) {
    val (hue, presence) = dominantHue(art) ?: return
    fun color(hue: Float, value: Float, alpha: Float) =
        android.graphics.Color.HSVToColor((alpha * presence * 255).toInt(), floatArrayOf((hue + 360f) % 360f, STAGE_SATURATION, value))

    val paint = Paint().apply { isDither = true }
    paint.color = color(hue, 0.12f, 1f)
    canvas.drawRect(0f, 0f, width, height, paint)
    fun light(color: Int, x: Float, y: Float, radius: Float) {
        paint.shader = RadialGradient(x, y, radius, color, color and 0xFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
    }
    light(color(hue, 0.85f, 0.95f), width * 0.8f, height * 0.1f, width * 0.85f)
    light(color(hue - 24f, 0.55f, 0.8f), width * 0.08f, height * 0.85f, width * 0.6f)
}

/**
 * The art's strongest hue, and how much of the art carries it (0..1). Reading the hue alone keeps
 * a red logo on a white banner red, where averaging its pixels would wash it out. Null when the
 * art has no real color.
 */
private fun dominantHue(art: Bitmap): Pair<Float, Float>? {
    val small = Bitmap.createScaledBitmap(art, HUE_SAMPLE_WIDTH, HUE_SAMPLE_HEIGHT, true)
    val weights = FloatArray(HUE_BUCKETS)
    val hsv = FloatArray(3)
    for (y in 0 until HUE_SAMPLE_HEIGHT) {
        for (x in 0 until HUE_SAMPLE_WIDTH) {
            android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
            if (hsv[1] > 0.4f && hsv[2] > 0.3f) {
                weights[(hsv[0] / 360f * HUE_BUCKETS).toInt() % HUE_BUCKETS] += hsv[1] * hsv[2]
            }
        }
    }
    val peak = weights.indices.maxBy { weights[it] }
    val neighbors = (-2..2).map { offset -> offset to weights[(peak + offset).mod(HUE_BUCKETS)] }
    val weight = neighbors.sumOf { it.second.toDouble() }.toFloat()
    if (weight == 0f) return null
    val center = peak + neighbors.sumOf { (offset, w) -> (offset * w).toDouble() }.toFloat() / weight
    val share = weight / (HUE_SAMPLE_WIDTH * HUE_SAMPLE_HEIGHT)
    val presence = ((share - HUE_MIN_SHARE) * HUE_PRESENCE_GAIN).coerceIn(0f, 1f)
    if (presence == 0f) return null
    return (center + 0.5f) / HUE_BUCKETS * 360f to presence
}

private const val STAGE_SATURATION = 0.88f
private const val HUE_SAMPLE_WIDTH = 32
private const val HUE_SAMPLE_HEIGHT = 18
private const val HUE_BUCKETS = 36
/** Below this share of colored art (a rainbow Apple logo on black, say), the wallpaper stays. */
private const val HUE_MIN_SHARE = 0.03f
private const val HUE_PRESENCE_GAIN = 12f
private const val PHOTO_OVERLAY_WIDTH = 240
private const val PHOTO_OVERLAY_HEIGHT = 135
/** Short, as the GPU draws two full-screen images for as long as it runs. */
private const val BACKDROP_FADE_MS = 350
private const val BACKDROP_SETTLE_MS = 150L
