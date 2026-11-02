package app.lumen.feature.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberFrostedWindow
import kotlin.math.roundToInt

internal class AppOptions(
    val onEditHomeScreen: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onHide: () -> Unit,
    val onAppInfo: () -> Unit,
)

/** Shown beside a tile when select is held on it, like tvOS's context menu. [anchor] is the tile's bounds in the window. */
@Composable
internal fun AppOptionsDialog(
    app: AppInfo,
    anchor: Rect,
    isFavorite: Boolean,
    options: AppOptions,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(300.dp), anchor = anchor) { firstRow ->
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = remember(app.icon) { app.icon.asImageBitmap() }
            Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)), filterQuality = FilterQuality.High)
            Spacer(Modifier.width(14.dp))
            LumenText(app.label, LumenType.Title, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))

        MenuRow("Edit Home Screen", run(options.onEditHomeScreen), Modifier.focusRequester(firstRow))
        MenuRow(if (isFavorite) "Remove from Top Shelf" else "Add to Top Shelf", run(options.onToggleFavorite))
        MenuRow("Hide App", run(options.onHide), subtitle = "Bring it back from Settings › Hidden Apps")
        MenuRow("App Info", run(options.onAppInfo))
    }
}

/**
 * A sheet of glass floating over a frosted copy of the screen, at [alignment], or beside [anchor]
 * (bounds in the window) when there is one. [content] gets a requester to put on whichever element
 * should take focus first.
 */
@Composable
internal fun GlassDialog(
    onDismiss: () -> Unit,
    alignment: Alignment,
    panelModifier: Modifier,
    anchor: Rect? = null,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    // Captured out here, from the home screen's window; a dialog has a window of its own.
    val frost = rememberFrostedWindow()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

        Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
            Crossfade(targetState = frost, animationSpec = tween(220), label = "frost") { image ->
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Column(
                (if (anchor != null) Modifier.beside(anchor) else Modifier)
                    .then(panelModifier)
                    .graphicsLayer {
                        val scale = 0.94f + 0.06f * appear.value
                        scaleX = scale
                        scaleY = scale
                        alpha = appear.value.coerceIn(0f, 1f)
                    }
                    .liquidGlass(LumenDimens.GlassCorner, tint = if (frost != null) LumenColors.GlassTint else LumenColors.GlassTintOpaque)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content(first)
            }
        }
    }
}

/** Places the panel to the right of [anchor], or to its left when there's no room, top-aligned with it and kept on screen. */
private fun Modifier.beside(anchor: Rect) = layout { measurable, constraints ->
    val panel = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    // Clears the tile's focus scale and shadow.
    val gap = 28.dp.roundToPx()
    val right = anchor.right.roundToInt() + gap
    val x = if (right + panel.width <= constraints.maxWidth) right else (anchor.left.roundToInt() - gap - panel.width).coerceAtLeast(0)
    val y = (anchor.top.roundToInt() - gap).coerceIn(0, (constraints.maxHeight - panel.height).coerceAtLeast(0))
    layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
}

