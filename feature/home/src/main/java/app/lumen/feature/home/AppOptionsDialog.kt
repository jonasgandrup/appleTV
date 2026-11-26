package app.lumen.feature.home

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.GlassDialog
import app.lumen.core.designsystem.LocalOverlayClose
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
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
    // Coming back from the Move to list lands on the row that led there, as going back does everywhere else.
    var cameBack by remember { mutableStateOf(false) }
    val moveRow = remember { FocusRequester() }
    GlassDialog(
        onDismiss,
        Alignment.Center,
        Modifier.width(272.dp),
        anchor = anchor,
        // Back steps out of the Move to list first, and only then closes the menu.
        onBack = {
            choosingMove.also {
                if (it) {
                    cameBack = true
                    choosingMove = false
                }
            }
        },
    ) { firstRow ->
        val run = rememberRun(onDismiss)
        // The rows are replaced when moving between the menu and its Move to list, so focus has to follow.
        LaunchedEffect(choosingMove) {
            withFrameNanos { }
            runCatching { (if (!choosingMove && cameBack) moveRow else firstRow).requestFocus() }
        }
        if (choosingMove) {
            // Says which list this is, now that the row that led here is gone.
            LumenText("Move to", LumenType.Section, Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp), color = LumenColors.TextSecondary)
            options.moveTargets.forEachIndexed { i, target ->
                MenuRow(target.label, run { options.onMove(target) }, if (i == 0) Modifier.focusRequester(firstRow) else Modifier)
            }
        } else {
            val edit = options.onEditHomeScreen
            if (edit != null) MenuRow("Edit Home Screen", run(edit), Modifier.focusRequester(firstRow))
            MenuRow(
                "Move to",
                { choosingMove = true },
                (if (edit == null) Modifier.focusRequester(firstRow) else Modifier).focusRequester(moveRow),
                chevron = true,
            )
            MenuRow("Hide App", run(options.onHide))
            options.onDelete?.let { MenuRow("Delete App", run(it)) }
            MenuRow("App Info", run(options.onAppInfo))
        }
    }
}

/** The menu for a program held down in a row. [onRemove] is null for what an app merely recommends, which has nothing of the user's to take away. */
@Composable
internal fun ProgramOptionsDialog(
    anchor: Rect,
    onRemove: (() -> Unit)?,
    appLabel: String?,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(350.dp), anchor = anchor) { firstRow ->
        val run = rememberRun(onDismiss)
        if (onRemove != null) MenuRow("Remove from Continue Watching", run(onRemove), Modifier.focusRequester(firstRow))
        if (appLabel != null) MenuRow("Open $appLabel", run(onOpenApp), if (onRemove == null) Modifier.focusRequester(firstRow) else Modifier)
    }
}

/** The menu for a folder held down in the grid. */
@Composable
internal fun FolderOptionsDialog(anchor: Rect, onEditHomeScreen: () -> Unit, onRename: () -> Unit, onDismiss: () -> Unit) {
    GlassDialog(onDismiss, Alignment.Center, Modifier.width(272.dp), anchor = anchor) { firstRow ->
        val run = rememberRun(onDismiss)
        MenuRow("Edit Home Screen", run(onEditHomeScreen), Modifier.focusRequester(firstRow))
        MenuRow("Rename Folder", run(onRename))
    }
}

/**
 * Wraps a menu row's action so choosing it first closes the menu, the way it opened, and then acts.
 * Read inside the menu's own content, where [LocalOverlayClose] is the menu's.
 */
@Composable
internal fun rememberRun(onDismiss: () -> Unit): (() -> Unit) -> () -> Unit {
    val close = LocalOverlayClose.current
    return { action ->
        {
            close {
                onDismiss()
                action()
            }
        }
    }
}
