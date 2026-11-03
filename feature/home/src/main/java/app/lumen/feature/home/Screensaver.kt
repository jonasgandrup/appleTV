package app.lumen.feature.home

import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.core.designsystem.LocalFocusIdle
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.frosted
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberClockText
import app.lumen.core.settings.ScreensaverType
import app.lumen.core.settings.WallpaperLibrary
import app.lumen.core.system.NowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val AERIAL_MS = 75_000L
private const val SLIDE_MS = 40_000
private const val VEIL_OUT_MS = 900
private const val VEIL_IN_MS = 1_400
private const val FIRST_FRAME_TIMEOUT_MS = 4_000L
private const val CROSSFADE_MS = 2_500
private const val DETAILS_MS = 6_000L
private const val PHOTO_WIDTH = 1920
/** A track change can report "not playing" for a moment, which shouldn't bring the scenery back. */
private const val MUSIC_LINGER_MS = 5_000L
/** One pixel at a time, this rarely, so the drift is too slow to see and costs almost nothing to draw. */
private const val DRIFT_STEP_MS = 2_000L
private const val DRIFT_X_PX = 30
private const val DRIFT_Y_PX = 21
private const val MUSIC_FADE_MS = 1_000

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
 * change scene, Down names what's showing, and anything else stops it. The key that stops it is
 * swallowed, so waking never opens an app. While [nowPlaying] is playing, the album art takes over
 * from the scenery, and any key stops it.
 *
 * While the timer runs Lumen holds the screen on, so Google TV's own ambient mode can't start
 * first; it lets go [MAX_SCREENSAVER_MS] into the screen saver, unless [nowPlaying] is playing, since
 * the TV sleeping would cut the music off. With [holdOff], the screen saver doesn't start by itself,
 * though Back can still start one.
 */
@Composable
internal fun ScreensaverHost(
    timeoutMinutes: Int,
    type: ScreensaverType,
    showClock: Boolean,
    holdOff: Boolean,
    nowPlaying: NowPlaying?,
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
    val isPlaying = nowPlaying?.isPlaying == true
    var musicShown by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            musicShown = true
        } else {
            delay(MUSIC_LINGER_MS)
            musicShown = false
        }
    }
    // Stays on the last song while it fades away, even once its app has closed its session.
    var song by remember { mutableStateOf<NowPlaying?>(null) }
    if (nowPlaying != null) song = nowPlaying
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

    LaunchedEffect(resumed, timeoutMinutes, inputCount, holdOff) {
        expired = false
        if (!resumed || timeoutMinutes <= 0) return@LaunchedEffect
        delay(timeoutMinutes * 60_000L)
        if (!holdOff) showing = true
        delay(MAX_SCREENSAVER_MS)
        expired = true
    }

    val view = LocalView.current
    val keepScreenOn = resumed && timeoutMinutes > 0 && (!expired || nowPlaying?.isPlaying == true)
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    /** The screen saver's answer to a key pressed while it runs. False means it should stop. */
    fun handleKey(key: Key): Boolean {
        if (musicShown) return false
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
        // Nothing behind the screen saver needs to keep moving.
        CompositionLocalProvider(LocalFocusIdle provides (focusIdle && !showing)) { content() }
        AnimatedVisibility(showing, enter = fadeIn(tween(1_500)), exit = fadeOut(tween(300))) {
            Screensaver(type.takeIf { it in categories } ?: ScreensaverType.Aerials, showClock, photos, categories, controls, song.takeIf { musicShown })
        }
    }
}

/** One of the bundled clips, rendered by tools/render_aerials.py so that each loops perfectly. */
private enum class Aerial(val asset: String, val title: String, val subtitle: String) {
    Ocean("ocean", "Open Water", "Sunset"),
    Aurora("aurora", "Northern Lights", "Above the Lake"),
    Clouds("clouds", "Above the Clouds", "Sunset"),
    Ridges("ridges", "Ridges", "Mist at Sunrise"),
    Dunes("dunes", "Dunes", "Desert"),
    Earth("earth", "Earth", "From Orbit"),
}

/** Moving nature scenes, as tvOS's Aerials are, or the user's photos drifting slowly. */
@Composable
private fun Screensaver(
    type: ScreensaverType,
    showClock: Boolean,
    photos: List<String>,
    categories: List<ScreensaverType>,
    controls: SaverControls,
    music: NowPlaying?,
) {
    val index = controls.slide
    val aerial = Aerial.entries[index.mod(Aerial.entries.size)].takeIf { type == ScreensaverType.Aerials }
    LaunchedEffect(type, index) {
        delay(if (aerial != null) AERIAL_MS else SLIDE_MS.toLong())
        controls.slide = index + 1
    }
    // Named briefly when it starts, then it's only the picture and the time, until Down asks again.
    val showDetails = controls.showDetails
    LaunchedEffect(showDetails, index) {
        if (showDetails) {
            delay(DETAILS_MS)
            controls.showDetails = false
        }
    }

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        // Stays on the last song while it fades away.
        var lastMusic by remember { mutableStateOf(music) }
        if (music != null) lastMusic = music
        Crossfade(targetState = music != null, animationSpec = tween(MUSIC_FADE_MS), label = "musicScene") { showMusic ->
            val shown = lastMusic
            when {
                showMusic && shown != null -> MusicScene(shown)
                aerial != null -> AerialVideo(aerial)
                else -> PhotoSlides(photos, index)
            }
        }
        AnimatedVisibility(showDetails && music == null, enter = fadeIn(tween(500)), exit = fadeOut(tween(800))) {
            Details(aerial?.title ?: "Your Photos", aerial?.subtitle)
        }
        // Changes sides with each scene, so it never stays in one place on the panel.
        val clockLeft = index % 2 != 0
        AnimatedVisibility(
            showClock || showDetails,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(800)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Crossfade(targetState = clockLeft, animationSpec = tween(CROSSFADE_MS), label = "clockSide") { left ->
                Box(Modifier.fillMaxSize().padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)) {
                    TimeOfDay(Modifier.align(if (left) Alignment.BottomStart else Alignment.BottomEnd), alignEnd = !left)
                }
            }
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

/**
 * Plays [aerial] on repeat. Video is decoded in hardware, so it's far lighter on the Chromecast than
 * animating anything comparable would be. Scenes change through black, once the next is really showing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AerialVideo(aerial: Aerial) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val veil = remember { Animatable(1f) }
    LaunchedEffect(aerial) {
        veil.animateTo(1f, tween(VEIL_OUT_MS))
        val showing = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showing.complete(Unit)
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri("asset:///aerials/${aerial.asset}.mp4"))
        player.prepare()
        withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { showing.await() }
        player.removeListener(listener)
        veil.animateTo(0f, tween(VEIL_IN_MS))
    }
    AndroidView(factory = { TextureView(it).also(player::setVideoTextureView) }, modifier = Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = veil.value) })
}

/** The user's photos, each zooming and panning slowly. The next is ready before it's shown, so nothing flashes black. */
@Composable
private fun PhotoSlides(photos: List<String>, index: Int) {
    var shown by remember { mutableStateOf<Pair<Int, ImageBitmap?>?>(null) }
    LaunchedEffect(photos, index) {
        if (photos.isEmpty()) return@LaunchedEffect
        shown = index to withContext(Dispatchers.IO) { WallpaperLibrary.decode(photos[index.mod(photos.size)], PHOTO_WIDTH)?.asImageBitmap() }
    }
    Crossfade(targetState = shown, animationSpec = tween(CROSSFADE_MS), label = "slide") { slide ->
        val image = slide?.second
        if (image != null) {
            KenBurns(reverse = slide.first % 2 != 0) {
                Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

/** The scene's name along the bottom, over a soft shade, as tvOS names an Aerial. The clock sits on whichever side it isn't. */
@Composable
private fun Details(title: String, subtitle: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(vertical = LumenDimens.SafeVertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LumenText(title, LumenType.Title)
            subtitle?.let { LumenText(it, LumenType.Section, color = LumenColors.TextSecondary) }
        }
    }
}

/** The time and date, with a soft shadow so they read over a bright sky. */
@Composable
private fun TimeOfDay(modifier: Modifier, alignEnd: Boolean) {
    val (time, date) = rememberClockText()
    val shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 14f)
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        LumenText(time, LumenType.Title.copy(shadow = shadow))
        LumenText(date, LumenType.Section.copy(shadow = shadow), color = LumenColors.TextSecondary)
    }
}

/**
 * What's playing, filling the screen as tvOS's Now Playing does: the album cover large, with the
 * song and artist beside it, over a soft blur of the cover. Everything creeps slowly around so it
 * doesn't stay in one place on the panel.
 */
@Composable
private fun MusicScene(playing: NowPlaying) {
    val blur by produceState<ImageBitmap?>(null, playing.art) {
        val art = playing.art
        value = art?.let { withContext(Dispatchers.Default) { runCatching { it.frosted().asImageBitmap() }.getOrNull() } }
    }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_STEP_MS)
            step++
        }
    }
    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        Crossfade(targetState = blur, animationSpec = tween(MUSIC_FADE_MS), label = "musicBackdrop") { image ->
            if (image != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = MUSIC_BACKDROP_DIM)))
                }
            }
        }
        AnimatedContent(
            targetState = playing,
            contentKey = { it.title to it.artist },
            transitionSpec = { fadeIn(tween(MUSIC_FADE_MS)) togetherWith fadeOut(tween(MUSIC_FADE_MS / 2)) using null },
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = drift(step, DRIFT_X_PX).toFloat()
                    translationY = drift(step, DRIFT_Y_PX).toFloat()
                },
            label = "musicSong",
        ) { song ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                AlbumCover(song)
                Column(Modifier.widthIn(max = MusicTextMaxWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LumenText(song.title, LumenType.Title, maxLines = 3)
                    song.artist?.let { LumenText(it, LumenType.Headline, color = LumenColors.TextSecondary, maxLines = 2) }
                }
            }
        }
    }
}

/** The cover, smaller when its image is too small to stay sharp, or a plain note when there's none. */
@Composable
private fun AlbumCover(playing: NowPlaying) {
    val shape = RoundedCornerShape(16.dp)
    val art = remember(playing.art) { playing.art?.asImageBitmap() }
    if (art != null) {
        val size = if (art.width >= SHARP_COVER_PX) CoverSize else SmallCoverSize
        Image(art, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    } else {
        Box(Modifier.size(SmallCoverSize).background(LumenColors.Surface, shape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_status_music), null, Modifier.size(72.dp))
        }
    }
}

/** A slow back-and-forth of whole pixels between -[amplitude] and [amplitude]. */
private fun drift(step: Int, amplitude: Int): Int = abs((step + amplitude).mod(amplitude * 4) - amplitude * 2) - amplitude

private const val MUSIC_BACKDROP_DIM = 0.4f
private const val SHARP_COVER_PX = 400
private val CoverSize = 300.dp
private val SmallCoverSize = 200.dp
private val MusicTextMaxWidth = 440.dp

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
