package app.lumen.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Told of each key pressed in a window over the home screen, which the home screen itself never sees. */
val LocalRemoteActivity = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Closes the [GlassOverlay] it's read in the way it opened, shrinking back to where it came from, and
 * then runs what it's given: the caller's own dismissal, and whatever the choice made leads to. Outside
 * an overlay there's nothing to close, so that runs at once.
 */
val LocalOverlayClose = staticCompositionLocalOf<(() -> Unit) -> Unit> { { then -> then() } }

/**
 * A sheet of glass floating over the dimmed screen, at [alignment], or beside [anchor] (bounds in
 * the window) when there is one. The screen stays sharp around it, as on tvOS; only the glass
 * frosts it. [content] gets a requester to put on whichever element should take focus first.
 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    alignment: Alignment,
    panelModifier: Modifier,
    anchor: Rect? = null,
    /** Back's own answer before it closes the dialog, as when it steps back from a submenu; true when taken. */
    onBack: (() -> Boolean)? = null,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    GlassOverlay(
        onDismiss = onDismiss,
        alignment = alignment,
        onBack = onBack,
        // A menu grows out of the tile it belongs to, and goes back into it.
        origin = anchor?.center,
        fromScale = if (anchor != null) ANCHORED_FROM_SCALE else OVERLAY_FROM_SCALE,
    ) { frost, first ->
        Column(
            (if (anchor != null) Modifier.beside(anchor) else Modifier)
                .then(panelModifier)
                .liquidGlass(LumenDimens.GlassCorner, tint = if (frost != null) LumenColors.GlassTint else LumenColors.GlassTintOpaque)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content(first)
        }
    }
}

/**
 * The window behind [GlassDialog], Control Center and folders: a light dim over the screen, a pop as
 * it appears, and glass inside it frosting a capture of the screen. [content] gets that capture,
 * null until it's ready and if it can't be taken, and the requester for what should take focus first.
 *
 * It leaves the way it came, as things do on tvOS: Back, and anything inside that closes it through
 * [LocalOverlayClose], shrinks and fades it back toward where it grew from before [onDismiss] runs.
 * That's [origin], a point in the window such as the middle of the tile that opened it, or else
 * [pivot], a place on the overlay's own content.
 */
@Composable
fun GlassOverlay(
    onDismiss: () -> Unit,
    alignment: Alignment,
    /** How far the screen behind darkens, higher for glass with busy things showing around it. */
    dim: Float = 0.3f,
    /** Back's own answer before it closes this, as when it steps back from a panel inside; true when taken. */
    onBack: (() -> Boolean)? = null,
    origin: Offset? = null,
    pivot: TransformOrigin = TransformOrigin.Center,
    fromScale: Float = OVERLAY_FROM_SCALE,
    content: @Composable BoxScope.(ImageBitmap?, FocusRequester) -> Unit,
) {
    // Captured out here, from the home screen's window; a dialog has a window of its own.
    val frost = rememberFrostedWindow()
    val appear = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = remember {
        { then ->
            if (!closing) {
                closing = true
                scope.launch {
                    // From wherever it has got to, so closing it while it's still opening doesn't jump.
                    appear.animateTo(0f, tween(OVERLAY_EXIT_MS, easing = FastOutLinearInEasing))
                    then()
                }
            }
        }
    }
    Dialog(
        onDismissRequest = { if (onBack?.invoke() != true) close { dismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Android dims behind a dialog by itself, more than the screen should be here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

        val activity = LocalRemoteActivity.current
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) activity()
                    // On its way out, it takes no more presses: a second one would act on what's leaving.
                    closing
                }
                // Comes and goes with the glass, rather than cutting in ahead of it.
                .drawBehind { drawRect(Color.Black, alpha = dim * appear.value.coerceIn(0f, 1f)) },
            contentAlignment = alignment,
        ) {
            CompositionLocalProvider(LocalLiveFrost provides null, LocalGlassBackdrop provides frost, LocalOverlayClose provides close) {
                Box(
                    Modifier.graphicsLayer {
                        val scale = fromScale + (1f - fromScale) * appear.value
                        scaleX = scale
                        scaleY = scale
                        alpha = appear.value.coerceIn(0f, 1f)
                        transformOrigin = origin
                            ?.takeIf { size.width > 0f && size.height > 0f }
                            ?.let { TransformOrigin((it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) }
                            ?: pivot
                    },
                ) {
                    content(frost, first)
                }
            }
        }
    }
}

private const val OVERLAY_FROM_SCALE = 0.94f
/** A menu beside a tile starts smaller, so it reads as coming out of the tile rather than appearing over it. */
private const val ANCHORED_FROM_SCALE = 0.86f
/** Shorter than the way in: what's been dismissed shouldn't linger. */
private const val OVERLAY_EXIT_MS = 160

/**
 * Asks for a line of text, as tvOS does for a folder's name: select the field to bring up the
 * system's keyboard. Blank text can't be saved unless [allowBlank].
 */
@Composable
fun TextEntryDialog(
    title: String,
    initial: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(420.dp)) { first ->
        var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
        var focused by remember { mutableStateOf(false) }
        val close = LocalOverlayClose.current
        fun finish() {
            val text = value.text.trim()
            close {
                if (text.isNotEmpty() || allowBlank) onDone(text)
                onDismiss()
            }
        }
        LumenText(title, LumenType.Headline, Modifier.padding(start = 6.dp, bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = LumenType.Headline.copy(color = if (focused) LumenColors.OnFocused else LumenColors.TextPrimary),
            cursorBrush = SolidColor(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(first)
                .onFocusChanged { focused = it.hasFocus }
                .background(if (focused) LumenColors.Focused else LumenColors.Surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
        MenuRow("Done", ::finish)
    }
}

/**
 * Keeps the tile in view and as few of its neighbors covered as can be. A tile low on the screen, like
 * one in the top row at rest, gets the panel above it, centered on it. Others get it beside them, to
 * the right or else to the left, top-aligned. Always inside the safe area.
 */
private fun Modifier.beside(anchor: Rect) = layout { measurable, constraints ->
    val panel = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    // Clears the tile's focus scale and shadow.
    val gap = 28.dp.roundToPx()
    val marginX = LumenDimens.SafeHorizontal.roundToPx()
    val marginY = LumenDimens.SafeVertical.roundToPx()
    val above = anchor.center.y > constraints.maxHeight / 2 && anchor.top.roundToInt() - gap - panel.height >= marginY
    val x: Int
    val y: Int
    if (above) {
        x = (anchor.center.x.roundToInt() - panel.width / 2).coerceIn(marginX, (constraints.maxWidth - panel.width - marginX).coerceAtLeast(marginX))
        y = anchor.top.roundToInt() - gap - panel.height
    } else {
        val right = anchor.right.roundToInt() + gap
        val fitsRight = right + panel.width <= constraints.maxWidth - marginX
        x = if (fitsRight) right else (anchor.left.roundToInt() - gap - panel.width).coerceAtLeast(0)
        y = (anchor.top.roundToInt() - gap).coerceIn(marginY, (constraints.maxHeight - panel.height - marginY).coerceAtLeast(marginY))
    }
    layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
}
