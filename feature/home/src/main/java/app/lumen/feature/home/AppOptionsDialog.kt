package app.lumen.feature.home

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.GlassDialog
import app.lumen.core.designsystem.MenuRow

/** Where an app can go from tvOS's "Move to" menu. */
internal sealed interface MoveTarget {
    val label: String

    data object TopRow : MoveTarget {
        override val label get() = "Top Row"
    }

    data object HomeScreen : MoveTarget {
        override val label get() = "Home Screen"
    }

    data object NewFolder : MoveTarget {
        override val label get() = "New Folder"
    }

    data class Folder(val id: String, override val label: String) : MoveTarget
}

internal class AppOptions(
    /** Null inside a folder, where apps aren't dragged around. */
    val onEditHomeScreen: (() -> Unit)?,
    val moveTargets: List<MoveTarget>,
    val onMove: (MoveTarget) -> Unit,
    val onHide: () -> Unit,
    /** Null for apps that came with the TV and can't be removed. */
    val onDelete: (() -> Unit)?,
    val onAppInfo: () -> Unit,
)

/** Shown beside a tile when select is held on it, like tvOS's context menu: the tile stays in view, so the menu needn't name it. [anchor] is the tile's bounds in the window. */
@Composable
internal fun AppOptionsDialog(anchor: Rect, options: AppOptions, onDismiss: () -> Unit) {
    var choosingMove by remember { mutableStateOf(false) }
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(300.dp), anchor = anchor) { firstRow ->
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        // The rows are replaced when moving between the menu and its Move to list, so focus has to follow.
        LaunchedEffect(choosingMove) {
            withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
        if (choosingMove) {
            options.moveTargets.forEachIndexed { i, target ->
                MenuRow(target.label, run { options.onMove(target) }, if (i == 0) Modifier.focusRequester(firstRow) else Modifier)
            }
        } else {
            val edit = options.onEditHomeScreen
            if (edit != null) MenuRow("Edit Home Screen", run(edit), Modifier.focusRequester(firstRow))
            MenuRow("Move to", { choosingMove = true }, if (edit == null) Modifier.focusRequester(firstRow) else Modifier, chevron = true)
            MenuRow("Hide App", run(options.onHide))
            options.onDelete?.let { MenuRow("Delete App", run(it)) }
            MenuRow("App Info", run(options.onAppInfo))
        }
    }
}

/** The menu for a folder held down in the grid. */
@Composable
internal fun FolderOptionsDialog(anchor: Rect, onEditHomeScreen: () -> Unit, onRename: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(300.dp), anchor = anchor) { firstRow ->
        fun run(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        MenuRow("Edit Home Screen", run(onEditHomeScreen), Modifier.focusRequester(firstRow))
        MenuRow("Rename Folder", run(onRename))
    }
}
