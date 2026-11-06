package app.lumen.feature.home

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.core.designsystem.Avatar
import app.lumen.core.designsystem.GlassOverlay
import app.lumen.core.designsystem.animatePressableScale
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberClockText
import app.lumen.core.designsystem.tvClickable
import app.lumen.core.system.DeviceStatus
import app.lumen.core.system.NetworkStatus
import app.lumen.core.system.NowPlaying
import app.lumen.core.system.SleepTimer
import kotlinx.coroutines.delay

internal class ControlCenterActions(
    val onSleep: () -> Unit,
    val onScreensaver: () -> Unit,
    val onNetwork: () -> Unit,
    val onSound: () -> Unit,
    val onProfile: () -> Unit,
    val onSearch: () -> Unit,
    val onAccessibility: () -> Unit,
)

/** What Control Center shows of the device right now. */
internal class ControlCenterStatus(
    val network: NetworkStatus?,
    val headphones: String?,
    val nowPlaying: NowPlaying?,
    val nowPlayingApp: String?,
    val sleepEndsAt: Long?,
    val clickSounds: Boolean,
    val device: DeviceStatus,
)

private enum class ControlPage { Main, Sound, Network, SleepTimer }

/** The tabs along the top, as on tvOS, which come and go with what's active. */
private enum class ControlTab { NowPlaying, General }

private const val CONTROL_CENTER_DIM = 0.55f
/**
 * Darker than other glass: Control Center floats over whatever's on screen, often bright artwork, and
 * its small words have to read over all of it.
 */
private val ControlTint = Color(0x59000000)
private val ColumnGap = 8.dp
/** Measured from the picture in Apple's user guide, where these take about a tenth of the screen's width each. */
private val TileWidth = 102.dp
private val TileHeight = 54.dp
private val RoundButtonSize = 46.dp
private val SmallRoundButtonSize = 42.dp
private val TabSize = 28.dp
private val PanelWidth = TileWidth * 2 + ColumnGap
/** For what needs room for words: what's playing, and the panels behind the tiles. */
private val WidePanelWidth = 264.dp

/** Minutes offered by the Sleep Timer. */
private val SleepTimerOptions = listOf(15, 30, 60, 90, 120)

/**
 * tvOS's Control Center: loose glass tiles in the top-right corner, with no panel behind them, the
 * screen still in view around them. A row along the top has the time, status icons and tabs. It opens
 * on the general tab with focus on the tab itself, so a click right after opening does nothing drastic,
 * and Left and Right move between tabs, with Now Playing there only while something is. Sound, Wi-Fi
 * and the Sleep Timer open their own glass panels in place, and Back returns from them. [sleepReady]
 * says whether Sleep can act yet, or has to lead to setting up the Home Button service.
 */
@Composable
internal fun ControlCenter(
    actions: ControlCenterActions,
    profileName: String,
    profilePicture: ImageBitmap?,
    sleepReady: Boolean,
    status: ControlCenterStatus,
    onClickSounds: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(ControlPage.Main) }
    var chosenTab by remember { mutableStateOf(ControlTab.General) }
    // Its session can end while the tab is showing.
    val tab = if (status.nowPlaying == null) ControlTab.General else chosenTab
    val context = LocalContext.current
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    GlassOverlay(
        onDismiss = { if (page == ControlPage.Main) onDismiss() else page = ControlPage.Main },
        alignment = Alignment.TopEnd,
        // Its tiles float loose, so whatever's behind shows between them and has to stay back.
        dim = CONTROL_CENTER_DIM,
    ) { _, first ->
        // The buttons are replaced when moving between pages, so focus has to follow.
        LaunchedEffect(page) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        Column(
            Modifier
                .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)
                .width(if (page == ControlPage.Main && tab == ControlTab.General) PanelWidth else WidePanelWidth),
            verticalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            val minutesLeft = rememberMinutesLeft(status.sleepEndsAt)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp).height(TabSize),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LumenText(rememberClockText().first, LumenType.Caption)
                if (status.network?.hasInternet == false) StatusGlyph("No internet", R.drawable.ic_status_no_internet)
                if (status.sleepEndsAt != null) StatusGlyph("Sleep Timer", R.drawable.ic_cc_timer)
                if (status.headphones != null) StatusGlyph("Headphones", R.drawable.ic_status_headphones)
                Spacer(Modifier.weight(1f))
                if (page == ControlPage.Main) {
                    if (status.nowPlaying != null) {
                        TabButton(selected = tab == ControlTab.NowPlaying, onFocused = { chosenTab = ControlTab.NowPlaying }, onClick = {}) { color ->
                            Image(painterResource(R.drawable.ic_status_music), "Now Playing", Modifier.size(15.dp), colorFilter = ColorFilter.tint(color))
                        }
                    }
                    TabButton(
                        selected = tab == ControlTab.General,
                        onFocused = { chosenTab = ControlTab.General },
                        onClick = {},
                        modifier = Modifier.focusRequester(first),
                    ) { color ->
                        Image(painterResource(R.drawable.ic_cc_tune), "Controls", Modifier.size(15.dp), colorFilter = ColorFilter.tint(color))
                    }
                    TabButton(selected = false, onFocused = {}, onClick = run(actions.onProfile)) {
                        Avatar(profileName, picture = profilePicture, size = TabSize - 4.dp)
                    }
                }
            }
            when (page) {
                ControlPage.Main -> when (tab) {
                    ControlTab.NowPlaying -> status.nowPlaying?.let { NowPlayingTile(it, status.nowPlayingApp, run { it.open(context) }) }
                    ControlTab.General -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                            ControlTile(
                                title = "Sleep",
                                icon = R.drawable.ic_cc_power,
                                onClick = run(actions.onSleep),
                                height = TileHeight * 2 + ColumnGap,
                                large = true,
                                caption = "Set Up".takeUnless { sleepReady },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(ColumnGap)) {
                                ControlTile(
                                    if (status.network?.kind == NetworkStatus.Kind.Ethernet) "Ethernet" else "Wi-Fi",
                                    R.drawable.ic_cc_wifi,
                                    { page = ControlPage.Network },
                                    caption = connectionLabel(status.network),
                                )
                                ControlTile("Screen Saver", R.drawable.ic_cc_screensaver, run(actions.onScreensaver))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                            ControlTile("Sound", R.drawable.ic_cc_volume, { page = ControlPage.Sound }, caption = status.headphones ?: "TV")
                            ControlTile(
                                title = "Sleep Timer",
                                icon = R.drawable.ic_cc_timer,
                                onClick = if (sleepReady) ({ page = ControlPage.SleepTimer }) else run(actions.onSleep),
                                caption = minutesLeft?.let { "$it min left" },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(run(actions.onSearch)) { color -> RoundGlyph("Search", R.drawable.ic_cc_search, color) }
                            RoundButton(run(actions.onAccessibility)) { color -> RoundGlyph("Accessibility", R.drawable.ic_cc_accessibility, color) }
                        }
                    }
                }
                ControlPage.Sound -> SoundPanel(status, onClickSounds, run(actions.onSound), first)
                ControlPage.Network -> NetworkPanel(status.network, run(actions.onNetwork), first)
                ControlPage.SleepTimer -> SleepTimerPanel(status.sleepEndsAt, onDismiss, first)
            }
        }
    }
}

/** A small status icon by the time. */
@Composable
private fun StatusGlyph(description: String, @DrawableRes icon: Int) {
    Image(painterResource(icon), description, Modifier.size(14.dp), colorFilter = ColorFilter.tint(LumenColors.TextPrimary))
}

/** A tab along the top: it shows its page as soon as focus reaches it, as tvOS's do. [content] gets the color to draw it in. */
@Composable
private fun TabButton(
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val look = rememberFocusLook()
    Box(
        modifier
            .onFocusChanged {
                look.onFocus(it.isFocused)
                if (it.isFocused) onFocused()
            }
            .tvClickable(onPressedChange = look.onPressed, onClick = onClick)
            .graphicsLayer {
                val scale = look.scale()
                scaleX = scale
                scaleY = scale
            }
            .size(TabSize)
            .liquidGlass(
                LumenDimens.CapsuleCorner,
                tint = when {
                    look.focused -> LumenColors.GlassFocused
                    selected -> SelectedTabTint
                    else -> ControlTint
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(look.content)
    }
}

private val SelectedTabTint = Color(0x47FFFFFF)

private fun connectionLabel(network: NetworkStatus?): String = when {
    network == null || network.kind == NetworkStatus.Kind.None -> "Off"
    network.hasInternet -> "Connected"
    else -> "No Internet"
}

/** A sheet of glass in Control Center's place, for one of its panels. */
@Composable
private fun ControlPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(TileCorner, tint = ControlTint)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LumenText(title, LumenType.Headline, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), maxLines = 1)
        content()
    }
}

/**
 * Sound. The remote sets a TV's volume directly and an app can't reach it over HDMI, so the volume
 * buttons here are only for headphones, whose volume is the device's own.
 */
@Composable
private fun SoundPanel(status: ControlCenterStatus, onClickSounds: (Boolean) -> Unit, onSettings: () -> Unit, first: FocusRequester) {
    val headphones = status.headphones
    ControlPanel("Sound") {
        PanelText("Playing on ${headphones ?: "TV"}")
        if (headphones != null) {
            var volume by remember { mutableFloatStateOf(status.device.headphoneVolume()) }
            fun change(up: Boolean) {
                status.device.adjustVolume(up)
                volume = status.device.headphoneVolume()
            }
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoundButton({ change(false) }, Modifier.focusRequester(first), SmallRoundButtonSize) { color -> RoundGlyph("Volume down", R.drawable.ic_cc_minus, color) }
                Box(Modifier.weight(1f).height(6.dp).background(LumenColors.Surface, CircleShape)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(volume).background(LumenColors.TextPrimary, CircleShape))
                }
                RoundButton({ change(true) }, size = SmallRoundButtonSize) { color -> RoundGlyph("Volume up", R.drawable.ic_cc_plus, color) }
            }
        } else {
            PanelText("Use your remote's volume buttons to change the TV's volume.", LumenColors.TextSecondary)
        }
        MenuRow(
            "Click Sounds",
            { onClickSounds(!status.clickSounds) },
            if (headphones == null) Modifier.focusRequester(first) else Modifier,
            value = if (status.clickSounds) "On" else "Off",
        )
        MenuRow("Sound Settings", onSettings, chevron = true)
    }
}

/** What Android lets an app see of the connection. The network's name would take the location permission, so it's left out. */
@Composable
private fun NetworkPanel(network: NetworkStatus?, onSettings: () -> Unit, first: FocusRequester) {
    val title = when (network?.kind) {
        NetworkStatus.Kind.Ethernet -> "Ethernet"
        NetworkStatus.Kind.Other -> "Network"
        else -> "Wi-Fi"
    }
    ControlPanel(title) {
        PanelValue(
            "Status",
            when {
                network == null || network.kind == NetworkStatus.Kind.None -> "Not Connected"
                network.hasInternet -> "Connected"
                else -> "No Internet"
            },
        )
        network?.signalBars?.let { bars ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LumenText("Signal", LumenType.Body, Modifier.weight(1f), color = LumenColors.TextSecondary)
                SignalBars(bars)
            }
        }
        network?.ipAddress?.let { PanelValue("IP Address", it) }
        MenuRow("Network Settings", onSettings, Modifier.focusRequester(first), chevron = true)
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..4).forEach { bar ->
            Box(
                Modifier
                    .size(width = 5.dp, height = (bar * 4).dp)
                    .background(if (bar <= bars) LumenColors.TextPrimary else LumenColors.Surface, CircleShape),
            )
        }
    }
}

@Composable
private fun SleepTimerPanel(endsAt: Long?, onDismiss: () -> Unit, first: FocusRequester) {
    val minutesLeft = rememberMinutesLeft(endsAt)
    ControlPanel("Sleep Timer") {
        if (minutesLeft != null) {
            PanelText("Turning off in $minutesLeft min", LumenColors.TextSecondary)
            MenuRow(
                "Cancel Timer",
                {
                    SleepTimer.cancel()
                    onDismiss()
                },
                Modifier.focusRequester(first),
            )
        }
        SleepTimerOptions.forEachIndexed { index, minutes ->
            MenuRow(
                durationLabel(minutes),
                {
                    SleepTimer.start(minutes)
                    onDismiss()
                },
                if (index == 0 && minutesLeft == null) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    fun unit(count: Int, name: String) = if (count == 1) "1 $name" else "$count ${name}s"
    return when {
        hours == 0 -> unit(rest, "Minute")
        rest == 0 -> unit(hours, "Hour")
        else -> "${unit(hours, "Hour")} ${unit(rest, "Minute")}"
    }
}

/** Whole minutes until [endsAt], rounded up, kept current while on screen; null when there's no timer. */
@Composable
private fun rememberMinutesLeft(endsAt: Long?): Int? {
    val minutes by produceState<Int?>(null, endsAt) {
        while (endsAt != null) {
            val left = endsAt - SystemClock.elapsedRealtime()
            value = ((left + 59_999) / 60_000).toInt().coerceAtLeast(1)
            delay(left.mod(60_000L) + 1)
        }
        value = null
    }
    return minutes
}

@Composable
private fun PanelText(text: String, color: Color = LumenColors.TextPrimary) {
    LumenText(text, LumenType.Body, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = color)
}

@Composable
private fun PanelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        LumenText(label, LumenType.Body, Modifier.weight(1f), color = LumenColors.TextSecondary, maxLines = 1)
        LumenText(value, LumenType.Body, maxLines = 1)
    }
}

/**
 * What's playing, from any app that shares it, on top of Control Center, while it's playing or
 * paused. As on tvOS, selecting the song opens its player, and the controls work from here.
 */
@Composable
private fun NowPlayingTile(playing: NowPlaying, appName: String?, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(TileCorner, tint = ControlTint)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val look = rememberFocusLook()
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .onFocusChanged { look.onFocus(it.isFocused) }
                .tvClickable(onClick = onOpen)
                .background(if (look.focused) LumenColors.GlassFocused else Color.Transparent, shape)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val art = remember(playing.art) { playing.art?.asImageBitmap() }
            if (art != null) {
                Image(art, null, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            }
            Column(Modifier.weight(1f)) {
                LumenText(playing.title, LumenType.Body, color = look.content, maxLines = 1)
                (playing.artist ?: appName)?.let { LumenText(it, LumenType.Caption, color = look.content.copy(alpha = 0.65f), maxLines = 1) }
            }
        }
        if (playing.durationMs > 0) Timeline(playing, Modifier.padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            RoundButton(playing::previous, size = SmallRoundButtonSize) { color -> RoundGlyph("Previous", R.drawable.ic_cc_previous, color) }
            RoundButton(playing::playPause, size = SmallRoundButtonSize) { color ->
                if (playing.isPlaying) RoundGlyph("Pause", R.drawable.ic_cc_pause, color) else RoundGlyph("Play", R.drawable.ic_play, color)
            }
            RoundButton(playing::next, size = SmallRoundButtonSize) { color -> RoundGlyph("Next", R.drawable.ic_cc_next, color) }
        }
    }
}

/** tvOS's timeline: elapsed time, a thin bar, and the time remaining. */
@Composable
internal fun Timeline(playing: NowPlaying, modifier: Modifier = Modifier) {
    val position by produceState(playing.positionMs(), playing) {
        while (true) {
            value = playing.positionMs()
            if (!playing.isPlaying) break
            delay(1_000L - value % 1_000L)
        }
    }
    val fraction = (position.toFloat() / playing.durationMs).coerceIn(0f, 1f)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LumenText(trackTime(position), LumenType.Caption, color = LumenColors.TextSecondary, maxLines = 1)
        Box(Modifier.weight(1f).height(4.dp).background(LumenColors.Surface, CircleShape)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(LumenColors.TextPrimary, CircleShape))
        }
        LumenText("-" + trackTime(playing.durationMs - position), LumenType.Caption, color = LumenColors.TextSecondary, maxLines = 1)
    }
}

private fun trackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    } else {
        "%d:%02d".format(minutes, seconds % 60)
    }
}

/** Grows and turns white with focus, the way every Control Center button does. */
@Composable
private fun rememberFocusLook(): FocusLook {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animatePressableScale(focused, pressed, 1.06f, "controlScale")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(120), label = "controlContent")
    return FocusLook(focused, { scale }, content, { focused = it }, { pressed = it })
}

private class FocusLook(
    val focused: Boolean,
    val scale: () -> Float,
    val content: Color,
    val onFocus: (Boolean) -> Unit,
    val onPressed: (Boolean) -> Unit,
)

@Composable
private fun ControlTile(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = TileHeight,
    large: Boolean = false,
    caption: String? = null,
) {
    val look = rememberFocusLook()
    Box(
        modifier
            .onFocusChanged { look.onFocus(it.isFocused) }
            .tvClickable(onPressedChange = look.onPressed, onClick = onClick)
            .graphicsLayer {
                val scale = look.scale()
                scaleX = scale
                scaleY = scale
            }
            .size(TileWidth, height)
            .liquidGlass(TileCorner, tint = if (look.focused) LumenColors.GlassFocused else ControlTint),
    ) {
        if (large) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icon), null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(look.content))
                LumenText(title, LumenType.Caption, Modifier.padding(top = 6.dp), color = look.content, maxLines = 1, textAlign = TextAlign.Center)
                if (caption != null) LumenText(caption, TileCaption, color = look.content.copy(alpha = 0.65f), maxLines = 1)
            }
        } else {
            Row(Modifier.align(Alignment.CenterStart).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(look.content))
                Column(Modifier.padding(start = 7.dp)) {
                    LumenText(title, TileTitle, color = look.content, maxLines = 2)
                    if (caption != null) LumenText(caption, TileCaption, color = look.content.copy(alpha = 0.65f), maxLines = 1)
                }
            }
        }
    }
}

/** A small round button with only a picture on it, as on tvOS. [content] gets the color to draw it in. */
@Composable
private fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = RoundButtonSize,
    content: @Composable (Color) -> Unit,
) {
    val look = rememberFocusLook()
    Box(
        modifier
            .onFocusChanged { look.onFocus(it.isFocused) }
            .tvClickable(onPressedChange = look.onPressed, onClick = onClick)
            .graphicsLayer {
                val scale = look.scale()
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (look.focused) LumenColors.GlassFocused else ControlTint),
        contentAlignment = Alignment.Center,
    ) {
        content(look.content)
    }
}

@Composable
private fun RoundGlyph(description: String, @DrawableRes icon: Int, color: Color) {
    Image(painterResource(icon), description, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
}

private val TileCorner = 22.dp
private val TileTitle = LumenType.Caption.copy(fontSize = 12.sp, lineHeight = 14.sp)
private val TileCaption = LumenType.Caption.copy(fontSize = 10.5.sp, lineHeight = 13.sp)
