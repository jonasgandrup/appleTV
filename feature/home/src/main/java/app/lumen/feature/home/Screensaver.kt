package app.lumen.feature.home

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.lumen.core.designsystem.GradientFill
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.rememberWallpaperImage
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.Wallpaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SLIDE_MS = 40_000
private const val CROSSFADE_MS = 2_500

/**
 * After this long, Lumen stops holding the screen on and lets the TV's own sleep take over.
 * Kept short because many TVs (OLEDs especially) shouldn't show one scene for hours.
 */
private const val MAX_SCREENSAVER_MS = 30 * 60_000L

/**
 * Wraps the home screen and fades in a screensaver after [timeoutMinutes] without input while
 * Lumen is in front. The key that wakes it is swallowed, so waking never opens an app.
 *
 * While the timer runs Lumen holds the screen on, so Google TV's own ambient mode can't start
 * first; it lets go [MAX_SCREENSAVER_MS] into the screensaver.
 */
@Composable
internal fun ScreensaverHost(
    timeoutMinutes: Int,
    wallpaper: Wallpaper,
    loadPhotos: suspend () -> List<String>,
    wake: Flow<Unit>,
    content: @Composable () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var inputCount by remember { mutableLongStateOf(0L) }
    var showing by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    var swallowKeyUp by remember { mutableStateOf(false) }

    LaunchedEffect(wake) { wake.collect { inputCount++ } }

    LaunchedEffect(resumed, timeoutMinutes, inputCount) {
        showing = false
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

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                when {
                    showing && event.type == KeyEventType.KeyDown -> {
                        swallowKeyUp = true
                        inputCount++
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
        content()
        AnimatedVisibility(showing, enter = fadeIn(tween(1_500)), exit = fadeOut(tween(300))) {
            Screensaver(wallpaper, loadPhotos)
        }
    }
}

/**
 * Slowly zooming, drifting slides with a large clock, in the spirit of the tvOS aerials. Shows the
 * user's own wallpaper photos if there are any, otherwise cycles the built-in gradients.
 */
@Composable
private fun Screensaver(wallpaper: Wallpaper, loadPhotos: suspend () -> List<String>) {
    val photos by produceState<List<String>?>(null) { value = loadPhotos() }
    var slide by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SLIDE_MS.toLong())
            slide++
        }
    }
    val firstPreset = (wallpaper as? Wallpaper.Gradient)?.preset?.ordinal ?: 0

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        val list = photos ?: return@Box
        Crossfade(targetState = slide, animationSpec = tween(CROSSFADE_MS), label = "slide") { index ->
            KenBurns(reverse = index % 2 == 1) {
                if (list.isNotEmpty()) {
                    PhotoSlide(list[index % list.size])
                } else {
                    val presets = GradientPreset.entries
                    GradientFill(presets[(firstPreset + index) % presets.size], Modifier.fillMaxSize())
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
            ),
        )
        // The clock swaps sides with each slide so nothing stays in one place on the panel.
        Crossfade(targetState = slide % 2 == 0, animationSpec = tween(CROSSFADE_MS), label = "clockSide") { left ->
            Box(Modifier.fillMaxSize().padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical * 2)) {
                ScreensaverClock(Modifier.align(if (left) Alignment.BottomStart else Alignment.BottomEnd), alignEnd = !left)
            }
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

@Composable
private fun PhotoSlide(path: String) {
    val image = rememberWallpaperImage(path)
    Box(Modifier.fillMaxSize()) {
        GradientFill(GradientPreset.Graphite, Modifier.fillMaxSize())
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ScreensaverClock(modifier: Modifier, alignEnd: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = remember(now, timePattern) { SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault()).format(Date(now))
    }
    val align = if (alignEnd) Alignment.End else Alignment.Start
    Column(modifier, horizontalAlignment = align) {
        LumenText(time, LumenType.Display.copy(fontSize = 88.sp, lineHeight = 92.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp))
        LumenText(date, LumenType.Headline, color = LumenColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
    }
}
