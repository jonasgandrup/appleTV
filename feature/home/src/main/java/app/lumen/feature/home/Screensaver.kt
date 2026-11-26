package app.lumen.feature.home

import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import app.lumen.core.designsystem.LocalGlassBackdrop
import app.lumen.core.designsystem.LocalRemoteActivity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
/** How long a cover opened with a click stays up, so nothing large stays put on the panel if it's left there. */
private const val COVER_MS = 60_000L
private const val MUSIC_FADE_MS = 1_000
/** Long enough for the scene's first frame to be up on its view. */
private const val SCENE_FROST_DELAY_MS = 300L
private const val SCENE_FRAME_WIDTH = 320
private const val SCENE_FRAME_HEIGHT = 180

/**
 * After this long, Lumen stops holding the screen on and lets the TV's own sleep take over.
 * Kept short because many TVs (OLEDs especially) shouldn't show one scene for hours.
 */
private const val MAX_SCREENSAVER_MS = 30 * 60_000L


/** What the remote is doing to a running screen saver. */
@Stable
private class SaverControls {
    /** Moves by itself as time passes, and with Left and Right. */
    var slide by mutableIntStateOf(0)
    var showDetails by mutableStateOf(true)
    /** Which entry of the category picker is highlighted; null when it's closed. */
    var picking by mutableStateOf<Int?>(null)
    /** The song's cover is filling the screen, after a click on the song card. */
    var showCover by mutableStateOf(false)
}

/**
 * Wraps the home screen and fades in a screen saver after [timeoutMinutes] without input while
 * Lumen is in front. The remote works as on tvOS while it runs: Up picks a category, Left and Right
 * change scene, Down names what's showing, and anything else stops it. The key that stops it is
 * swallowed, so waking never opens an app. While [nowPlaying] is playing, a small card names the song
 * over the scenery. A click fills the screen with its cover, and a click, Back or any direction goes
 * back to the scenery, where Back stops the screen saver as usual.
 *
 * While the timer runs Lumen holds the screen on, so Google TV's own ambient mode can't start
 * first; it lets go [MAX_SCREENSAVER_MS] into the screen saver, unless [nowPlaying] is playing, since
 * the TV sleeping would cut the music off and the scenery keeps the screen moving meanwhile. With
 * [holdOff], the screen saver doesn't start by itself, though Back can still start one. Windows over
 * the home screen would sit in front of it, so [onStart] closes them as it starts, and it stops if
 * one opens ([overlayOpen]).
 */
@Composable
internal fun ScreensaverHost(
    timeoutMinutes: Int,
    type: ScreensaverType,
    showClock: Boolean,
    holdOff: Boolean,
    overlayOpen: Boolean,
    onStart: () -> Unit,
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
    val music = song.takeIf { musicShown }
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
    LaunchedEffect(overlayOpen) { if (overlayOpen) showing = false }
    LaunchedEffect(showing) {
        if (!showing) return@LaunchedEffect
        onStart()
        controls.picking = null
        controls.showDetails = true
        controls.showCover = false
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
    val keepScreenOn = resumed && timeoutMinutes > 0 && (!expired || (isPlaying && showing))
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    /** The screen saver's answer to a key pressed while it runs. False means it should stop. */
    fun handleKey(key: Key): Boolean {
        if (controls.showCover) {
            if (key !in CoverClosingKeys) return false
            controls.showCover = false
            return true
        }
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
            // With only one category there's nothing to pick, so Up leaves the picture alone.
            Key.DirectionUp -> if (categories.size > 1) controls.picking = categories.indexOf(type).coerceAtLeast(0)
            Key.DirectionDown -> controls.showDetails = !controls.showDetails
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (music == null) return false
                controls.showCover = true
            }
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
        // Once the opaque screen saver has fully faded in, what's behind it needn't be drawn on every frame.
        val saverState = remember { MutableTransitionState(false) }
        saverState.targetState = showing
        val covered = remember { derivedStateOf { saverState.isIdle && saverState.currentState } }
        val onRemoteActivity = remember { { inputCount++; Unit } }
        Box(Modifier.fillMaxSize().drawWithContent { if (!covered.value) drawContent() }) {
            CompositionLocalProvider(LocalRemoteActivity provides onRemoteActivity) { content() }
        }
        AnimatedVisibility(saverState, enter = fadeIn(tween(1_500)), exit = fadeOut(tween(300))) {
            Screensaver(type.takeIf { it in categories } ?: ScreensaverType.Aerials, showClock, photos, categories, controls, music)
        }
    }
}

private val CoverClosingKeys = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back,
)

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
        // The glass here frosts the scene, which is out of the home screen's frost's reach.
        var sceneFrost by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(controls.showCover, music == null) {
            if (music != null && controls.showCover) delay(COVER_MS)
            controls.showCover = false
        }
        Crossfade(targetState = controls.showCover, animationSpec = tween(MUSIC_FADE_MS), label = "musicScene") { showCover ->
            val shown = lastMusic
            when {
                showCover && shown != null -> MusicScene(shown)
                aerial != null -> AerialVideo(aerial) { sceneFrost = it }
                else -> PhotoSlides(photos, index) { sceneFrost = it }
            }
        }
        AnimatedVisibility(showDetails && !controls.showCover, enter = fadeIn(tween(500)), exit = fadeOut(tween(800))) {
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
        CompositionLocalProvider(LocalGlassBackdrop provides sceneFrost) {
            // On the side the clock isn't, and it changes sides too.
            AnimatedVisibility(
                music != null && !controls.showCover,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(800)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Crossfade(targetState = clockLeft, animationSpec = tween(CROSSFADE_MS), label = "songSide") { left ->
                    Box(Modifier.fillMaxSize().padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)) {
                        lastMusic?.let { SongCard(it, Modifier.align(if (left) Alignment.BottomEnd else Alignment.BottomStart)) }
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
}

/**
 * Plays [aerial] on repeat. Video is decoded in hardware, so it's far lighter on the Chromecast than
 * animating anything comparable would be. Scenes change through black, once the next is really showing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun AerialVideo(aerial: Aerial, onFrost: (ImageBitmap) -> Unit) {
    val context = LocalContext.current
    var texture by remember { mutableStateOf<TextureView?>(null) }
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
        launch {
            delay(SCENE_FROST_DELAY_MS)
            val frame = texture?.getBitmap(SCENE_FRAME_WIDTH, SCENE_FRAME_HEIGHT) ?: return@launch
            onFrost(withContext(Dispatchers.Default) { frame.frosted().asImageBitmap() })
        }
        veil.animateTo(0f, tween(VEIL_IN_MS))
    }
    AndroidView(
        factory = { TextureView(it).also(player::setVideoTextureView).also { view -> texture = view } },
        modifier = Modifier.fillMaxSize(),
    )
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = veil.value) })
}

/** The user's photos, each zooming and panning slowly. The next is ready before it's shown, so nothing flashes black. */
@Composable
private fun PhotoSlides(photos: List<String>, index: Int, onFrost: (ImageBitmap) -> Unit) {
    var shown by remember { mutableStateOf<Pair<Int, ImageBitmap?>?>(null) }
    LaunchedEffect(photos, index) {
        if (photos.isEmpty()) return@LaunchedEffect
        val photo = withContext(Dispatchers.IO) { WallpaperLibrary.decode(photos[index.mod(photos.size)], PHOTO_WIDTH) }
        photo?.let { onFrost(withContext(Dispatchers.Default) { it.frosted().asImageBitmap() }) }
        shown = index to photo?.asImageBitmap()
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
 * The song, filling the screen as tvOS's Now Playing does: the album cover large and centered, with
 * the song and artist under it, over a soft blur of the cover.
 */
@Composable
private fun MusicScene(playing: NowPlaying) {
    val blur by produceState<ImageBitmap?>(null, playing.art) {
        val art = playing.art
        value = art?.let { withContext(Dispatchers.Default) { runCatching { it.frosted().asImageBitmap() }.getOrNull() } }
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
            modifier = Modifier.align(Alignment.Center),
            label = "musicSong",
        ) { song ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumCover(song)
                Row(
                    Modifier.padding(top = 16.dp).widthIn(max = MusicTextMaxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AudioBars(song.isPlaying, Modifier)
                    LumenText(song.title, LumenType.Headline, Modifier.weight(1f, fill = false), maxLines = 1)
                }
                song.artist?.let { LumenText(it, LumenType.Body, Modifier.widthIn(max = MusicTextMaxWidth), color = LumenColors.TextSecondary, maxLines = 1) }
                if (song.durationMs > 0) Timeline(song, Modifier.padding(top = 14.dp).width(CoverSize))
            }
        }
    }
}

/** The cover, or a plain note when there's none. */
@Composable
private fun AlbumCover(playing: NowPlaying) {
    val shape = RoundedCornerShape(16.dp)
    val art = remember(playing.art) { playing.art?.asImageBitmap() }
    if (art != null) {
        Image(art, null, Modifier.size(CoverSize).clip(shape), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    } else {
        Box(Modifier.size(CoverSize).background(LumenColors.Surface, shape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_status_music), null, Modifier.size(72.dp))
        }
    }
}

/** What's playing, on a small sheet of glass over the scenery, with its artwork when the app shares some. */
@Composable
private fun SongCard(playing: NowPlaying, modifier: Modifier) {
    Row(
        modifier
            .widthIn(max = SongCardMaxWidth)
            .liquidGlass(16.dp, tint = LumenColors.GlassTintDim)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val art = remember(playing.art) { playing.art?.asImageBitmap() }
        if (art != null) {
            Image(art, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        }
        Column(Modifier.weight(1f, fill = false)) {
            LumenText(playing.title, LumenType.Body, maxLines = 1)
            playing.artist?.let { LumenText(it, LumenType.Caption, color = LumenColors.TextSecondary, maxLines = 1) }
        }
        AudioBars(playing.isPlaying, Modifier.padding(end = 6.dp))
    }
}

/** Three small bars that move while music plays and lie flat when it doesn't. Read while drawing, so they never recompose anything. */
@Composable
private fun AudioBars(moving: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "audioBars")
    val levels = AudioBarPeriodsMs.map { period ->
        transition.animateFloat(
            initialValue = AUDIO_BAR_MIN,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "audioBar",
        )
    }
    Canvas(modifier.size(width = 14.dp, height = 14.dp)) {
        val gap = 2.dp.toPx()
        val width = (size.width - gap * (levels.size - 1)) / levels.size
        levels.forEachIndexed { i, level ->
            val height = size.height * (if (moving) level.value else AUDIO_BAR_MIN)
            drawRoundRect(
                LumenColors.TextPrimary,
                topLeft = Offset(i * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

private val AudioBarPeriodsMs = listOf(430, 610, 520)
private const val AUDIO_BAR_MIN = 0.2f

private val SongCardMaxWidth = 320.dp
private const val MUSIC_BACKDROP_DIM = 0.4f
private val CoverSize = 300.dp
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
