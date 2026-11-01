package app.lumen.core.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes an element focusable and selectable with the remote's center button.
 *
 * Holding select fires [onLongClick] once the key starts repeating (~0.5 s). A key-up only
 * counts as a click if this element also saw the key-down, so releasing select after a
 * long-press opened a dialog doesn't activate whatever the dialog focused first.
 * Taps work too, for testing in the emulator with a mouse. Plays [LocalUiSounds] when focus
 * arrives from a D-pad press and on select.
 */
fun Modifier.tvClickable(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val sounds = LocalUiSounds.current
    val click by rememberUpdatedState({ sounds?.select(); onClick() })
    val longClick by rememberUpdatedState(onLongClick?.let { { sounds?.select(); it() } })
    var pressed by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }

    this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { click() },
                onLongPress = { longClick?.invoke() },
            )
        }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in DirectionKeys) sounds?.onDirectionPressed()
            false
        }
        .onKeyEvent { event ->
            if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                return@onKeyEvent false
            }
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val repeat = event.nativeKeyEvent.repeatCount
                    if (repeat == 0) {
                        pressed = true
                        longPressed = false
                    } else if (pressed && !longPressed && longClick != null) {
                        longPressed = true
                        pressed = false
                        longClick?.invoke()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (pressed && !longPressed) click()
                    pressed = false
                    longPressed = false
                    true
                }
                else -> false
            }
        }
        .onFocusChanged { if (it.isFocused) sounds?.onFocusMoved() }
        .focusable()
}

private val DirectionKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
