package app.lumen.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.UpNextItem
import app.lumen.core.designsystem.LiveFrost
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.WallpaperBackground
import app.lumen.core.designsystem.backgroundFrosted
import app.lumen.core.designsystem.blurred
import app.lumen.core.designsystem.renderGradient
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What sits on the stage above the dock: the focused app's row of programs, as tvOS's Top Shelf
 * shows a row of tiles for an app that has one. Up moves into it from the dock and Select plays. The
 * focused tile already says which app it belongs to, so nothing here repeats its name.
 */
@Composable
internal fun Hero(
    shelfArt: ShelfArt?,
    visible: Boolean,
    firstRowTile: FocusRequester,
    onProgramOptions: (UpNextItem, Rect) -> Unit,
    onRecommendationFocused: (UpNextItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wanted = shelfArt?.takeIf { visible && it.recommendations.isNotEmpty() }
    // Another app's row waits for focus to rest a moment, so gliding along the dock doesn't build and
    // throw away a row of large tiles for every app passed, and the work of building one stays out of
    // the first frames of the move. The same app's row, coming back or refreshed, shows at once.
    var shown by remember { mutableStateOf(wanted) }
    val lastShown = remember { arrayOf(wanted?.packageName) }
    LaunchedEffect(wanted) {
        if (wanted != null && wanted.packageName != lastShown[0]) delay(ROW_SETTLE_MS)
        shown = wanted
        if (wanted != null) lastShown[0] = wanted.packageName
    }
    Box(modifier) {
        // Keyed by app, so one app's row fades out as the next one's fades in, and gone entirely
        // while browsing below, so focus can't land in it from the grid.
        AnimatedContent(
            // The last app's row goes at once, rather than waiting with the next.
            targetState = shown?.takeIf { it.packageName == wanted?.packageName },
            contentKey = { it?.packageName },
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using null },
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            label = "recommendations",
        ) { target ->
            // Down into the space the list leaves above the dock's glass, so the row sits right over it.
            if (target != null) {
                // Faded here, as fadeIn and fadeOut would draw both whole rows into offscreen buffers on every move along the dock.
                val rowAlpha = transition.animateFloat(
                    transitionSpec = { tween(if (targetState == EnterExitState.Visible) 300 else 150) },
                    label = "rowAlpha",
                ) { if (it == EnterExitState.Visible) 1f else 0f }
                UpNextRow(
                    items = target.recommendations,
                    heading = target.heading,
                    firstTile = firstRowTile,
                    onOptions = onProgramOptions,
                    onItemFocused = onRecommendationFocused,
                    tileWidth = LumenDimens.ShelfRowTileWidth,
                    tileHeight = LumenDimens.ShelfRowTileHeight,
                    focusScale = SHELF_ROW_FOCUS_SCALE,
                    modifier = Modifier
                        .offset(y = LumenDimens.SafeVertical - 6.dp)
                        .graphicsLayer {
                            alpha = rowAlpha.value
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                )
            }
        }
    }
}

/** Large tiles grow less, so they stay clear of their neighbors. */
private const val SHELF_ROW_FOCUS_SCALE = 1.05f
/** Shorter than the old row's fade out, so the stage never stands empty between two rows. */
private const val ROW_SETTLE_MS = 100L

/**
 * The [hero]'s own row of programs from its TV channels, once focus has rested on it for a moment, so
 * gliding along the dock doesn't fetch tiles for every app passed. A row already loaded shows at
 * once. Holds the last row until the next arrives; check its package before using it.
 *
 * What's shown stays until something better replaces it. Coming back from an app rereads it, and
 * again a little later, since apps can take a moment to publish what was just watched. So does a
 * change in [removedPrograms], which can change what's on the stage.
 */
@Composable
internal fun rememberShelfArt(hero: State<AppInfo?>, enabled: Boolean, removedPrograms: Map<String, String>, viewModel: HomeViewModel): State<ShelfArt?> {
    val art = remember { mutableStateOf<ShelfArt?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, removedPrograms) {
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
                    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                    art.value = viewModel.refreshShelfArt(app)
                }
                lifecycle.currentStateFlow
                    .map { it.isAtLeast(Lifecycle.State.RESUMED) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest { resumed ->
                        if (!resumed) return@collectLatest
                        delay(RESUME_SETTLE_MS)
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
 * The stage behind everything: a deep blur of the focused program in the app's row ([glow]), or of
 * the app's own tile, as Apple's apps tint the screen behind what's in focus, plus scrims that keep
 * text legible. With neither, the wallpaper. While browsing [below] the Top Shelf it melts into a
 * frosted copy of itself, as tvOS's does.
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
    /** A thumbnail whose color lights the stage. */
    glow: Bitmap?,
    below: Boolean,
    frost: LiveFrost,
) {
    val belowAmount by animateFloatAsState(if (below) 1f else 0f, tween(450), label = "below")
    val preset = (wallpaper as? Wallpaper.Gradient)?.preset
    val art = app?.art
    val accent = app?.accentColor
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    // True while an opaque bake hides a photo wallpaper completely, so it needn't be drawn.
    var coversWallpaper by remember { mutableStateOf(false) }
    LaunchedEffect(preset, art, glow) {
        val first = current == null
        // Quick presses along the dock restart this effect before anything is baked or faded.
        if (!first) delay(BACKDROP_SETTLE_MS)
        val (next, nextFrost, nextBackground) = withContext(Dispatchers.Default) {
            // A washed-out tile has no color worth blurring; the app's own color lights the stage instead.
            val washedOut = glow == null && art != null && isWashedOut(art)
            val baked = bakeBackdrop(preset, glow ?: art.takeUnless { washedOut }, if (washedOut) accent?.let(::accentWash) else null)
            // A transparent bake has nothing to frost by itself; glass falls back to the wallpaper's frost.
            val opaque = baked.takeUnless { it.hasAlpha() }
            // Already soft enough for the dock's glass to bend, so it needs no blur of its own.
            val clear = opaque
            val background = opaque?.backgroundFrosted()
            // Starts handing the pictures to the graphics chip now, rather than on the frame that first shows them.
            listOfNotNull(baked, clear, background).forEach(Bitmap::prepareToDraw)
            Triple(baked.asImageBitmap(), clear?.asImageBitmap(), background?.asImageBitmap())
        }
        val opaque = !next.hasAlpha
        coversWallpaper = coversWallpaper && opaque
        previous = current
        current = next
        frost.previous = frost.image
        frost.image = nextFrost
        frost.background = nextBackground
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
                val frosted = frost.background
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
 * Opaque over a blur of [source], over [wash], or over [preset]; transparent over a photo wallpaper
 * when there's none of them. Blocking.
 */
private fun bakeBackdrop(preset: GradientPreset?, source: Bitmap?, wash: Bitmap? = null): Bitmap {
    val bitmap = source?.let(::stageBlur)
        ?: wash
        ?: preset?.let(::renderGradient)
        ?: Bitmap.createBitmap(PHOTO_OVERLAY_WIDTH, PHOTO_OVERLAY_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
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
    if (source != null || wash != null || preset != null) bitmap.setHasAlpha(false)
    return bitmap
}

/**
 * The stage in an app's own color, for an app with no program in focus whose tile is mostly white or
 * grey, as a logo on a white card is: blurred, such a tile leaves the whole screen a flat grey. Deep
 * and lit from the top left, as the wallpapers are, and painted at that depth rather than brought to
 * the brightness a blurred picture gets, which turns a red or a blue garish. Null when the color is
 * itself a grey, which would do no better than the tile: the wallpaper shows instead. Blocking.
 */
private fun accentWash(accent: Int): Bitmap? {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(accent, hsv)
    if (hsv[1] < ACCENT_MIN_SATURATION) return null
    fun shade(saturation: Float, value: Float) = AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1] * saturation, value))
    val wash = Bitmap.createBitmap(STAGE_BLUR_WIDTH, STAGE_BLUR_HEIGHT, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        isDither = true
        shader = LinearGradient(
            0f, 0f, STAGE_BLUR_WIDTH.toFloat(), STAGE_BLUR_HEIGHT.toFloat(),
            intArrayOf(shade(0.75f, 0.5f), shade(0.85f, 0.32f), shade(0.9f, 0.13f)),
            null,
            Shader.TileMode.CLAMP,
        )
    }
    Canvas(wash).drawRect(0f, 0f, STAGE_BLUR_WIDTH.toFloat(), STAGE_BLUR_HEIGHT.toFloat(), paint)
    return wash
}

/** Bright and nearly colorless overall. A dark tile blurs to a dark stage, which is fine as it is. */
private fun isWashedOut(art: Bitmap): Boolean {
    val small = Bitmap.createScaledBitmap(art, WASH_SAMPLE_WIDTH, WASH_SAMPLE_HEIGHT, true)
    val pixels = IntArray(WASH_SAMPLE_WIDTH * WASH_SAMPLE_HEIGHT).also { small.getPixels(it, 0, WASH_SAMPLE_WIDTH, 0, 0, WASH_SAMPLE_WIDTH, WASH_SAMPLE_HEIGHT) }
    val hsv = FloatArray(3)
    var saturation = 0f
    var brightness = 0f
    for (pixel in pixels) {
        AndroidColor.colorToHSV(pixel, hsv)
        saturation += hsv[1] * hsv[2]
        brightness += hsv[2]
    }
    return brightness / pixels.size > WASH_MIN_BRIGHTNESS && saturation / pixels.size < WASH_MAX_SATURATION
}

private const val WASH_SAMPLE_WIDTH = 32
private const val WASH_SAMPLE_HEIGHT = 18
private const val WASH_MIN_BRIGHTNESS = 0.5f
private const val WASH_MAX_SATURATION = 0.25f
/** Below this an app's color is a grey, or the mud that an icon of many colors averages to, and would light the stage no better than its tile did. */
private const val ACCENT_MIN_SATURATION = 0.45f

/**
 * [source] blurred until no shape is left, only its colors, then evened out: richer in color, and
 * brought to one brightness whether the picture was dark or bright, so white text and tiles read on
 * it and a dim picture doesn't leave the screen black. Small, as the screen draws it stretched and
 * smooth. Blocking.
 */
private fun stageBlur(source: Bitmap): Bitmap {
    val soft = Bitmap.createScaledBitmap(source, STAGE_BLUR_WIDTH, STAGE_BLUR_HEIGHT, true).blurred(STAGE_BLUR_RADIUS)
    val pixels = IntArray(STAGE_BLUR_WIDTH * STAGE_BLUR_HEIGHT).also { soft.getPixels(it, 0, STAGE_BLUR_WIDTH, 0, 0, STAGE_BLUR_WIDTH, STAGE_BLUR_HEIGHT) }
    val luminance = pixels.sumOf { (0.2126 * (it shr 16 and 0xFF) + 0.7152 * (it shr 8 and 0xFF) + 0.0722 * (it and 0xFF)) / 255.0 } / pixels.size
    val gain = (STAGE_LUMINANCE / luminance.toFloat().coerceAtLeast(0.01f)).coerceIn(STAGE_MIN_GAIN, STAGE_MAX_GAIN)
    val matrix = ColorMatrix().apply {
        setSaturation(STAGE_SATURATION)
        postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
    }
    val out = Bitmap.createBitmap(STAGE_BLUR_WIDTH, STAGE_BLUR_HEIGHT, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(soft, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return out
}

private const val STAGE_BLUR_WIDTH = 160
private const val STAGE_BLUR_HEIGHT = 90
private const val STAGE_BLUR_RADIUS = 9
private const val STAGE_SATURATION = 1.35f
/** About as bright as a deep blue, so white text reads over any of them. */
private const val STAGE_LUMINANCE = 0.2f
/** A near-black picture stays dark rather than being lifted into grey noise, and a bright one is held well down. */
private const val STAGE_MIN_GAIN = 0.35f
private const val STAGE_MAX_GAIN = 1.8f
private const val PHOTO_OVERLAY_WIDTH = 240
private const val PHOTO_OVERLAY_HEIGHT = 135
/** Short, as the GPU draws two full-screen images for as long as it runs. */
private const val BACKDROP_FADE_MS = 350
private const val BACKDROP_SETTLE_MS = 150L
