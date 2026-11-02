package app.lumen.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberClockText
import app.lumen.core.designsystem.tvClickable

internal class ControlCenterActions(
    val onScreensaver: () -> Unit,
    val onSettings: () -> Unit,
    val onAndroidSettings: () -> Unit,
)

/** The glass panel on the right side of the screen, opened from the clock, like tvOS's Control Center. */
@Composable
internal fun ControlCenter(actions: ControlCenterActions, onDismiss: () -> Unit) {
    GlassDialog(
        onDismiss = onDismiss,
        alignment = Alignment.TopEnd,
        panelModifier = Modifier
            .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)
            .width(330.dp),
    ) { first ->
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        val (time, date) = rememberClockText()
        LumenText(time, LumenType.Display, Modifier.padding(start = 20.dp, top = 12.dp))
        LumenText(date, LumenType.Section, Modifier.padding(start = 20.dp), color = LumenColors.TextSecondary)
        Spacer(Modifier.height(18.dp))
        MenuRow("Screen Saver", run(actions.onScreensaver), Modifier.focusRequester(first))
        MenuRow("Settings", run(actions.onSettings))
        MenuRow("Android Settings", run(actions.onAndroidSettings))
    }
}

/** A round glass button with a gear, beside the clock in the top-right corner; opens Settings. */
@Composable
internal fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "settingsScale",
    )
    val iconColor by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(160), label = "settingsIcon")
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(SettingsButtonSize)
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else LumenColors.GlassTintDim),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_settings_gear),
            contentDescription = "Settings",
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

/** As tall as the clock's capsule, so the two sit level. */
private val SettingsButtonSize = 31.dp
