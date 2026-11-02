package app.lumen.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.roundToInt

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
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    GlassOverlay(onDismiss, alignment) { frost, first ->
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
 */
@Composable
fun GlassOverlay(
    onDismiss: () -> Unit,
    alignment: Alignment,
    content: @Composable BoxScope.(ImageBitmap?, FocusRequester) -> Unit,
) {
    // Captured out here, from the home screen's window; a dialog has a window of its own.
    val frost = rememberFrostedWindow()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Android dims behind a dialog by itself, more than the screen should be here.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = alignment) {
            CompositionLocalProvider(LocalLiveFrost provides null, LocalGlassBackdrop provides frost) {
                Box(
                    Modifier.graphicsLayer {
                        val scale = 0.94f + 0.06f * appear.value
                        scaleX = scale
                        scaleY = scale
                        alpha = appear.value.coerceIn(0f, 1f)
                    },
                ) {
                    content(frost, first)
                }
            }
        }
    }
}

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
        fun finish() {
            val text = value.text.trim()
            if (text.isNotEmpty() || allowBlank) onDone(text)
            onDismiss()
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

/** Places the panel to the right of [anchor], or to its left when there's no room, top-aligned with it and kept inside the safe area. */
private fun Modifier.beside(anchor: Rect) = layout { measurable, constraints ->
    val panel = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    // Clears the tile's focus scale and shadow.
    val gap = 28.dp.roundToPx()
    val right = anchor.right.roundToInt() + gap
    val fitsRight = right + panel.width <= constraints.maxWidth - LumenDimens.SafeHorizontal.roundToPx()
    val x = if (fitsRight) right else (anchor.left.roundToInt() - gap - panel.width).coerceAtLeast(0)
    val margin = LumenDimens.SafeVertical.roundToPx()
    val y = (anchor.top.roundToInt() - gap).coerceIn(margin, (constraints.maxHeight - panel.height - margin).coerceAtLeast(margin))
    layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
}
