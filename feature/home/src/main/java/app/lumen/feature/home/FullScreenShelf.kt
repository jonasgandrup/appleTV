package app.lumen.feature.home

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.openFeatured
import app.lumen.core.designsystem.GlassDialog
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.animatePressableScale
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.tvClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Top Shelf gone full screen, as Up does on tvOS: the dock slides away, and what the app
 * features fills the screen one program at a time, moving on by itself after a while, as Apple's
 * carousels do, with the current page dot filling up to say when. Down or Back returns to the dock.
 * The programs on either side are fetched ahead with [preload].
 *
 * Play and More Info sit side by side, as in Apple's carousel layouts. Left and Right move between
 * them and on through the programs. Apps publish one link per program, with no separate way to
 * their details, so More Info shows what they wrote here, and appears only when they wrote something.
 *
 * [shown] is the program whose picture is on screen, which trails [index] while the next one
 * decodes; the text and buttons follow it, so words and picture always match. The title stays small,
 * since artwork usually carries its own lettering, which a large title would only compete with.
 */
@Composable
internal fun FullScreenShelf(
    programs: List<ShelfProgram>,
    index: Int,
    shown: ShelfProgram?,
    onIndex: (Int) -> Unit,
    playFocus: FocusRequester,
    preload: suspend (ShelfProgram) -> Unit,
    /** Holds on the current program while something's open over it, whose frost is a capture of it. */
    paused: Boolean,
) {
    val sounds = LocalUiSounds.current
    val context = LocalContext.current
    val program = programs[index]
    val current = shown ?: program
    var infoFor by remember { mutableStateOf<ShelfProgram?>(null) }
    var moreInfoFocused by remember { mutableStateOf(false) }
    // Read only while drawing the dot, so its filling never recomposes this.
    var timer by remember { mutableFloatStateOf(0f) }

    // The next program may have no More Info, so focus can't be left on it.
    fun moveOn(step: Int) {
        if (moreInfoFocused) runCatching { playFocus.requestFocus() }
        onIndex((index + step).mod(programs.size))
    }

    val holding = paused || infoFor != null
    LaunchedEffect(index, programs, holding) {
        launch {
            preload(programs[(index + 1) % programs.size])
            preload(programs[(index - 1).mod(programs.size)])
        }
        timer = 0f
        if (holding) return@LaunchedEffect
        val start = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= ADVANCE_MS) break
            timer = elapsed.toFloat() / ADVANCE_MS
            delay(TIMER_STEP_MS)
        }
        moveOn(1)
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = LumenDimens.SafeHorizontal, bottom = LumenDimens.SafeVertical + 22.dp)
                .widthIn(max = TextMaxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) using null },
                contentAlignment = Alignment.BottomStart,
                label = "programText",
            ) { text ->
                if (text != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        LumenText(text.title, LumenType.Headline.copy(shadow = TextShade), maxLines = 1)
                        text.detail?.let { LumenText(it, LumenType.Body.copy(shadow = TextShade), color = StageSecondary, maxLines = 1) }
                        text.description?.let { LumenText(it, LumenType.Body.copy(shadow = TextShade), color = StageSecondary, maxLines = 2) }
                    }
                }
            }
            // Follows the program itself rather than its picture, so a press of Right while the picture decodes lands here and doesn't skip a program.
            val hasInfo = program.fullDescription != null
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShelfButton(
                    label = if (current.resume) "Resume" else "Play",
                    icon = R.drawable.ic_play,
                    onClick = { openFeatured(context, current) },
                    modifier = Modifier
                        .focusRequester(playFocus)
                        .onPreviewKeyEvent { event ->
                            val step = when (event.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> if (hasInfo) return@onPreviewKeyEvent false else 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (event.type == KeyEventType.KeyDown) {
                                moveOn(step)
                                sounds?.move()
                            }
                            true
                        },
                )
                if (hasInfo) {
                    ShelfButton(
                        label = "More Info",
                        onClick = { infoFor = program },
                        modifier = Modifier
                            .onFocusChanged { moreInfoFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown) {
                                    moveOn(1)
                                    sounds?.move()
                                }
                                true
                            },
                    )
                }
            }
        }
        if (programs.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = LumenDimens.SafeVertical),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                programs.indices.forEach { i ->
                    PageDot(selected = i == index, timer = { timer })
                }
            }
        }
    }
    infoFor?.let { info ->
        ProgramInfoDialog(
            program = info,
            onPlay = {
                infoFor = null
                openFeatured(context, info)
            },
            onDismiss = { infoFor = null },
        )
    }
}

/** A page dot. The current one opens into a small capsule that fills as its time on screen runs out. */
@Composable
private fun PageDot(selected: Boolean, timer: () -> Float) {
    val width by animateDpAsState(if (selected) SelectedDotWidth else DotSize, tween(250), label = "dotWidth")
    Box(
        Modifier
            .size(width, DotSize)
            .drawBehind {
                val corners = CornerRadius(size.height / 2)
                drawRoundRect(LumenColors.TextTertiary, cornerRadius = corners)
                if (selected) {
                    val filled = (size.width * timer()).coerceAtLeast(size.height)
                    drawRoundRect(LumenColors.TextPrimary, size = Size(filled, size.height), cornerRadius = corners)
                }
            },
    )
}

/** What the app wrote about a program, for when two lines on the stage aren't enough. */
@Composable
private fun ProgramInfoDialog(program: ShelfProgram, onPlay: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(InfoWidth)) { first ->
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LumenText(program.title, LumenType.Title, maxLines = 2)
            program.detail?.let { LumenText(it, LumenType.Body, color = LumenColors.TextSecondary, maxLines = 1) }
            program.fullDescription?.let {
                LumenText(it, LumenType.Body, Modifier.padding(top = 6.dp), color = LumenColors.TextSecondary, maxLines = INFO_MAX_LINES)
            }
            ShelfButton(
                label = if (program.resume) "Resume" else "Play",
                icon = R.drawable.ic_play,
                onClick = onPlay,
                modifier = Modifier.padding(top = 12.dp).focusRequester(first),
            )
        }
    }
}

/** tvOS's capsule: glass at rest, white and a little larger with focus, sinking back as it's clicked. */
@Composable
private fun ShelfButton(label: String, onClick: () -> Unit, modifier: Modifier, @DrawableRes icon: Int? = null) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animatePressableScale(focused, pressed, 1.08f, "shelfButtonScale")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(120), label = "shelfButtonContent")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onPressedChange = { pressed = it }, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(ButtonHeight)
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else LumenColors.GlassTintDim)
            .padding(start = if (icon != null) 18.dp else 24.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(content))
        LumenText(label, LumenType.Headline, color = content)
    }
}

/** Keeps the words readable over bright art, which the stage's light scrim leaves bright. */
private val TextShade = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)
private val StageSecondary = Color(0xD1FFFFFF)
private const val ADVANCE_MS = 9_000L
/** A pixel or so of the dot at a time, which doesn't take a full frame rate to look smooth. */
private const val TIMER_STEP_MS = 100L
private const val INFO_MAX_LINES = 10
private val TextMaxWidth = 560.dp
private val InfoWidth = 560.dp
private val ButtonHeight = 40.dp
private val DotSize = 6.dp
private val SelectedDotWidth = 22.dp
