package app.lumen.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.lumen.core.designsystem.LocalFocusIdle
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberClockText
import app.lumen.core.settings.ScreensaverType
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val SLIDE_MS = 40_000
private const val CROSSFADE_MS = 2_500
private const val DETAILS_MS = 6_000L
private const val PHOTO_WIDTH = 1920

/**
 * After this long, Lumen stops holding the screen on and lets the TV's own sleep take over.
 * Kept short because many TVs (OLEDs especially) shouldn't show one scene for hours.
 */
private const val MAX_SCREENSAVER_MS = 30 * 60_000L

/** How long the remote stays quiet before tiles settle into [LocalFocusIdle]. */
private const val FOCUS_IDLE_MS = 7_000L

/** What the remote is doing to a running screen saver. */
@Stable
private class SaverControls {
    /** Moves by itself as time passes, and with Left and Right. */
    var slide by mutableIntStateOf(0)
    var showDetails by mutableStateOf(true)
    /** Which entry of the category picker is highlighted; null when it's closed. */
    var picking by mutableStateOf<Int?>(null)
}

/**
 * Wraps the home screen and fades in a screen saver after [timeoutMinutes] without input while
 * Lumen is in front. The remote works as on tvOS while it runs: Up picks a category, Left and Right
 * change picture, Down names what's showing, and anything else stops it. The key that stops it is
 * swallowed, so waking never opens an app.
 *
 * While the timer runs Lumen holds the screen on, so Google TV's own ambient mode can't start
 * first; it lets go [MAX_SCREENSAVER_MS] into the screen saver.
 */
@Composable
internal fun ScreensaverHost(
    timeoutMinutes: Int,
    type: ScreensaverType,
    onTypeChange: (ScreensaverType) -> Unit,
    loadPhotos: suspend () -> List<String>,
    wake: Flow<Unit>,
    startNow: Flow<Unit>,
    content: @Composable () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var inputCount by remember { mutableLongStateOf(0L) }
    var showing by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    var swallowKeyUp by remember { mutableStateOf(false) }
    var focusIdle by remember { mutableStateOf(false) }
    val controls = remember { SaverControls() }
    // Your Photos is on offer only with photos to show.
    val photos by produceState<List<String>>(emptyList(), showing) { value = loadPhotos() }
    val categories = remember(photos) {
        ScreensaverType.entries.filter { it != ScreensaverType.Photos || photos.isNotEmpty() }
    }

    // Stopped here by whatever stops it, not by the timer restarting below: a quick press of Back
    // could otherwise start the screen saver, then have it stopped by that same press's restart.
    fun stop() {
        showing = false
        inputCount++
    }

    LaunchedEffect(wake) { wake.collect { stop() } }
    LaunchedEffect(resumed) { if (!resumed) showing = false }
    LaunchedEffect(startNow) { startNow.collect { showing = true } }
    LaunchedEffect(resumed, inputCount) {
        focusIdle = false
        if (!resumed) return@LaunchedEffect
        delay(FOCUS_IDLE_MS)
        focusIdle = true
    }
    LaunchedEffect(showing) {
        if (!showing) return@LaunchedEffect
        controls.picking = null
        controls.showDetails = true
    }

    LaunchedEffect(resumed, timeoutMinutes, inputCount) {
        expired = false
        if (!resumed || timeoutMinutes <= 0) return@LaunchedEffect
        delay(timeoutMinutes * 60_000L)
        showing = true
        delay(MAX_SCREENSAVER_MS)
        expired = true
    }

    val view = LocalView.current
    val keepScreenOn = resumed && timeoutMinutes > 0 && !expired
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    /** The screen saver's answer to a key pressed while it runs. False means it should stop. */
    fun handleKey(key: Key): Boolean {
        val picking = controls.picking
        if (picking != null) {
            when (key) {
                Key.DirectionLeft -> controls.picking = (picking - 1).mod(categories.size)
                Key.DirectionRight -> controls.picking = (picking + 1).mod(categories.size)
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    onTypeChange(categories[picking])
                    controls.slide = 0
                    controls.picking = null
                    controls.showDetails = true
                }
                else -> controls.picking = null
            }
            return true
        }
        when (key) {
            Key.DirectionRight -> controls.slide++
            Key.DirectionLeft -> controls.slide--
            Key.DirectionUp -> controls.picking = categories.indexOf(type).coerceAtLeast(0)
            Key.DirectionDown -> controls.showDetails = !controls.showDetails
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                when {
                    showing && event.type == KeyEventType.KeyDown -> {
                        swallowKeyUp = true
                        if (!handleKey(event.key)) stop()
                        true
                    }
                    swallowKeyUp && event.type == KeyEventType.KeyUp -> {
                        swallowKeyUp = false
                        true
                    }
                    else -> {
                        if (event.type == KeyEventType.KeyDown) inputCount++
                        false
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalFocusIdle provides focusIdle) { content() }
        AnimatedVisibility(showing, enter = fadeIn(tween(1_500)), exit = fadeOut(tween(300))) {
            Screensaver(type.takeIf { it in categories } ?: ScreensaverType.Aerials, photos, categories, controls)
        }
    }
}

private class Slide(val index: Int, val image: ImageBitmap?, val title: String?, val subtitle: String?)

/** Slow, drifting pictures, in the spirit of tvOS's Aerials: nothing else on screen until asked for. */
@Composable
private fun Screensaver(type: ScreensaverType, photos: List<String>, categories: List<ScreensaverType>, controls: SaverControls) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Slide?>(null) }
    // The next picture is ready before it's shown, however long it takes to paint, so nothing flashes black.
    LaunchedEffect(type, controls.slide, photos) {
        val index = controls.slide
        shown = when (type) {
            ScreensaverType.Aerials -> aerialScene(index).let { Slide(index, aerialImage(context, index), it.title, it.subtitle) }
            ScreensaverType.Photos -> {
                val image = withContext(Dispatchers.IO) { WallpaperLibrary.decode(photos[index.mod(photos.size)], PHOTO_WIDTH)?.asImageBitmap() }
                Slide(index, image, "Your Photos", null)
            }
        }
        delay(SLIDE_MS.toLong())
        controls.slide = index + 1
    }
    // Named briefly when it starts, then it's only the picture, until Down asks again.
    val showDetails = controls.showDetails
    LaunchedEffect(showDetails, controls.slide) {
        if (showDetails) {
            delay(DETAILS_MS)
            controls.showDetails = false
        }
    }

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        Crossfade(targetState = shown, animationSpec = tween(CROSSFADE_MS), label = "slide") { slide ->
            val image = slide?.image
            if (image != null) {
                KenBurns(reverse = slide.index % 2 == 1) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        AnimatedVisibility(
            showDetails && shown != null,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(800)),
        ) {
            Details(shown)
        }
        AnimatedVisibility(
            controls.picking != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            CategoryPicker(categories, controls.picking ?: 0)
        }
    }
}

/** Zooms and pans its content across one slide's lifetime. */
@Composable
private fun KenBurns(reverse: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(SLIDE_MS + CROSSFADE_MS, easing = LinearEasing)) }
    val direction = if (reverse) -1f else 1f
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                val scale = 1.06f + 0.1f * p
                scaleX = scale
                scaleY = scale
                translationX = direction * size.width * 0.025f * (2 * p - 1)
            },
    ) { content() }
}

/** What's showing and the time, at the bottom left over a soft shade, as tvOS names an Aerial. */
@Composable
private fun Details(slide: Slide?) {
    val (time, date) = rememberClockText()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)) {
            slide?.title?.let { LumenText(it, LumenType.Title) }
            slide?.subtitle?.let { LumenText(it, LumenType.Section, color = LumenColors.TextSecondary) }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical),
            horizontalAlignment = Alignment.End,
        ) {
            LumenText(time, LumenType.Title)
            LumenText(date, LumenType.Section, color = LumenColors.TextSecondary)
        }
    }
}

/** Up's row of categories along the top of the screen. It isn't focusable: the host's key handling drives it. */
@Composable
private fun CategoryPicker(categories: List<ScreensaverType>, highlighted: Int) {
    Row(
        Modifier.padding(top = LumenDimens.SafeVertical),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        categories.forEachIndexed { i, category ->
            val selected = i == highlighted
            LumenText(
                category.displayName,
                LumenType.Headline,
                Modifier
                    .liquidGlass(LumenDimens.CapsuleCorner, tint = if (selected) LumenColors.GlassFocused else LumenColors.GlassTintDim)
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                color = if (selected) LumenColors.OnFocused else LumenColors.TextPrimary,
            )
        }
    }
}
