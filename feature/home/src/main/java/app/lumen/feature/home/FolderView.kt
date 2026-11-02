package app.lumen.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.GlassOverlay
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.tvClickable

/**
 * An open folder, as on tvOS: its apps in the home screen's grid over a frosted screen, its name
 * above them. Select the name to rename it.
 */
@Composable
internal fun FolderView(folder: HomeItem.Folder, actions: TileActions, onRename: () -> Unit, onDismiss: () -> Unit) {
    GlassOverlay(onDismiss, Alignment.TopCenter) { frost, first ->
        Box(Modifier.fillMaxSize()) {
            if (frost != null) {
                Image(frost, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LumenDimens.SafeVertical, bottom = LumenDimens.SafeVertical),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FolderName(folder.folder.name, onRename)
                AppGrid(
                    tiles = remember(folder.apps) { folder.apps.map { GridTile.App(HomeItem.App(it)) } },
                    actions = actions,
                    onOpenSettings = {},
                    firstTileFocus = first,
                )
            }
        }
    }
}

/** A title that can be selected, turning into a white capsule with focus like other tvOS buttons. */
@Composable
private fun FolderName(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(120), label = "folderName")
    LumenText(
        name,
        LumenType.Title,
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else Color.Transparent)
            .padding(horizontal = 22.dp, vertical = 6.dp),
        color = content,
        maxLines = 1,
    )
}
