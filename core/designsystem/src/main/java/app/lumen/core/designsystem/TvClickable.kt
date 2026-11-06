package app.lumen.core.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf

/**
 * Makes an element focusable and selectable with the remote's center button.
 *
 * Holding select fires [onLongClick] once the key starts repeating (~0.5 s). A key-up only
 * counts as a click if this element also saw the key-down, so releasing select after a
 * long-press opened a dialog doesn't activate whatever the dialog focused first.
 * Taps work too, for testing in the emulator with a mouse. Plays [LocalUiSounds] when focus
 * arrives from a D-pad press and on select. [onPressedChange] hears select going down and coming
 * up, so the element can answer the click at once, as tvOS's do.
 *
 * A modifier node rather than a composed modifier: every tile on the home screen has one, and a node
 * costs no composition of its own, only an update when its callbacks change.
 */
fun Modifier.tvClickable(
    onLongClick: (() -> Unit)? = null,
    onPressedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier = this.then(TvClickableElement(onLongClick, onPressedChange, onClick)).focusable()

private data class TvClickableElement(
    val onLongClick: (() -> Unit)?,
    val onPressedChange: (Boolean) -> Unit,
    val onClick: () -> Unit,
) : ModifierNodeElement<TvClickableNode>() {
    override fun create() = TvClickableNode(onLongClick, onPressedChange, onClick)

    override fun update(node: TvClickableNode) {
        node.onLongClick = onLongClick
        node.onPressedChange = onPressedChange
        node.onClick = onClick
    }
}

private class TvClickableNode(
    var onLongClick: (() -> Unit)?,
    var onPressedChange: (Boolean) -> Unit,
    var onClick: () -> Unit,
) : DelegatingNode(), KeyInputModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var pressed = false
    private var longPressed = false
    /** Focus events also arrive when nothing changed, as when what's around this redraws, so only a real arrival clicks. */
    private var focused = false

    private val sounds: UiSounds? get() = currentValueOf(LocalUiSounds)

    private fun click() {
        sounds?.select()
        onClick()
    }

    private fun longClick(): Boolean {
        val action = onLongClick ?: return false
        sounds?.select()
        action()
        return true
    }

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                detectTapGestures(onTap = { click() }, onLongPress = { longClick() })
            },
        )
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && event.key in DirectionKeys) sounds?.onDirectionPressed()
        return false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                val repeat = event.nativeKeyEvent.repeatCount
                if (repeat == 0) {
                    pressed = true
                    longPressed = false
                    onPressedChange(true)
                } else if (pressed && !longPressed && onLongClick != null) {
                    longPressed = true
                    pressed = false
                    onPressedChange(false)
                    longClick()
                }
                true
            }
            KeyEventType.KeyUp -> {
                if (pressed) onPressedChange(false)
                if (pressed && !longPressed) click()
                pressed = false
                longPressed = false
                true
            }
            else -> false
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused && !focused) sounds?.onFocusMoved()
        focused = focusState.isFocused
        // Its key-up will go elsewhere, so nothing else would release it.
        if (!focusState.isFocused && pressed) {
            pressed = false
            onPressedChange(false)
        }
    }
}

private val DirectionKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
