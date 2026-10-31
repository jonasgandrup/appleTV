package app.lumen.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow

internal class AppOptions(
    val onOpen: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onMove: (offset: Int) -> Unit,
    val onHide: () -> Unit,
    val onAppInfo: () -> Unit,
)

/** Shown when select is held on a tile. */
@Composable
internal fun AppOptionsDialog(
    app: AppInfo,
    favoriteIndex: Int,
    favoriteCount: Int,
    options: AppOptions,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val firstRow = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(LumenColors.SurfaceRaised)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = remember(app.icon) { app.icon.asImageBitmap() }
                    Image(icon, null, Modifier.size(36.dp), filterQuality = FilterQuality.High)
                    Spacer(Modifier.width(14.dp))
                    LumenText(app.label, LumenType.Title, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))

                val isFavorite = favoriteIndex >= 0
                MenuRow("Open", run(options.onOpen), Modifier.focusRequester(firstRow))
                MenuRow(if (isFavorite) "Remove from Top Shelf" else "Add to Top Shelf", run(options.onToggleFavorite))
                if (isFavorite && favoriteIndex > 0) MenuRow("Move Left", run { options.onMove(-1) })
                if (isFavorite && favoriteIndex < favoriteCount - 1) MenuRow("Move Right", run { options.onMove(1) })
                MenuRow("Hide App", run(options.onHide), subtitle = "Bring it back from Settings › Hidden Apps")
                MenuRow("App Info", run(options.onAppInfo))
            }
        }
    }
}
