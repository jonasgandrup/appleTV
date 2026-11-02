package app.lumen.feature.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.Avatar
import app.lumen.core.designsystem.GlassOverlay
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberClockText
import app.lumen.core.designsystem.tvClickable

internal class ControlCenterActions(
    val onSleep: () -> Unit,
    val onScreensaver: () -> Unit,
    val onNetwork: () -> Unit,
    val onSound: () -> Unit,
    val onSettings: () -> Unit,
    val onProfile: () -> Unit,
    val onAndroidSettings: () -> Unit,
    val onSearch: () -> Unit,
    val onAccessibility: () -> Unit,
)

private val ColumnGap = 10.dp
private val TileWidth = 150.dp
private val TileHeight = 66.dp
private val RoundButtonSize = 52.dp

/**
 * tvOS's Control Center: loose glass tiles in the top-right corner, with no panel behind them,
 * the screen still in view around them. One large tile, smaller ones beside it, round buttons
 * below, the time above. [sleepReady] says whether Sleep can act yet, or has to lead to setting up
 * the Home Button service.
 */
@Composable
internal fun ControlCenter(actions: ControlCenterActions, profileName: String, sleepReady: Boolean, onDismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    GlassOverlay(onDismiss, Alignment.TopEnd) { _, first ->
        Column(
            Modifier
                .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)
                .width(TileWidth * 2 + ColumnGap),
            verticalArrangement = Arrangement.spacedBy(ColumnGap),
        ) {
            val (time, date) = rememberClockText()
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LumenText(date, LumenType.Caption, Modifier.weight(1f), color = LumenColors.TextSecondary, maxLines = 1)
                LumenText(time, LumenType.Headline)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                ControlTile(
                    title = "Sleep",
                    icon = R.drawable.ic_cc_power,
                    onClick = run(actions.onSleep),
                    height = TileHeight * 2 + ColumnGap,
                    large = true,
                    caption = "Set Up".takeUnless { sleepReady },
                    modifier = Modifier.focusRequester(first),
                )
                Column(verticalArrangement = Arrangement.spacedBy(ColumnGap)) {
                    ControlTile("Wi-Fi", R.drawable.ic_cc_wifi, run(actions.onNetwork))
                    ControlTile("Screen Saver", R.drawable.ic_cc_screensaver, run(actions.onScreensaver))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                ControlTile("Sound", R.drawable.ic_cc_volume, run(actions.onSound))
                ControlTile("Settings", R.drawable.ic_settings_gear, run(actions.onSettings))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ColumnGap), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(run(actions.onProfile)) { Avatar(profileName, size = 34.dp) }
                RoundButton(run(actions.onSearch)) { color -> RoundGlyph("Search", R.drawable.ic_cc_search, color) }
                RoundButton(run(actions.onAccessibility)) { color -> RoundGlyph("Accessibility", R.drawable.ic_cc_accessibility, color) }
                RoundButton(run(actions.onAndroidSettings)) { color -> RoundGlyph("Android Settings", R.drawable.ic_cc_tune, color) }
            }
        }
    }
}

/** Grows and turns white with focus, the way every Control Center button does. */
@Composable
private fun rememberFocusLook(): FocusLook {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "controlScale",
    )
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(120), label = "controlContent")
    return FocusLook(focused, { scale }, content) { focused = it }
}

private class FocusLook(val focused: Boolean, val scale: () -> Float, val content: Color, val onFocus: (Boolean) -> Unit)

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
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                val scale = look.scale()
                scaleX = scale
                scaleY = scale
            }
            .size(TileWidth, height)
            .liquidGlass(TileCorner, tint = if (look.focused) LumenColors.GlassFocused else LumenColors.GlassTint),
    ) {
        if (large) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icon), null, Modifier.size(52.dp), colorFilter = ColorFilter.tint(look.content))
                LumenText(title, LumenType.Body, Modifier.padding(top = 10.dp), color = look.content, maxLines = 1, textAlign = TextAlign.Center)
                if (caption != null) LumenText(caption, LumenType.Caption, color = look.content.copy(alpha = 0.6f), maxLines = 1)
            }
        } else {
            Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(look.content))
                LumenText(title, LumenType.Body, Modifier.padding(start = 10.dp), color = look.content, maxLines = 1)
            }
        }
    }
}

/** A small round button with only a picture on it, as on tvOS. [content] gets the color to draw it in. */
@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val look = rememberFocusLook()
    Box(
        Modifier
            .onFocusChanged { look.onFocus(it.isFocused) }
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                val scale = look.scale()
                scaleX = scale
                scaleY = scale
            }
            .size(RoundButtonSize)
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (look.focused) LumenColors.GlassFocused else LumenColors.GlassTint),
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
