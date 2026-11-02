package app.lumen.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.LumenDimens

/**
 * Edit Home Screen, as on tvOS: one app is lifted and follows the remote through the dock and the
 * grid, the others making room, until Select drops it. The arrangement lives here while the app
 * moves and is saved once, on drop, so each press answers instantly.
 */
@Stable
internal class HomeEditor {
    /** The lifted app; null when not editing. */
    var lifted by mutableStateOf<AppInfo?>(null)
        private set
    var dock by mutableStateOf(emptyList<AppInfo>())
        private set
    var grid by mutableStateOf(emptyList<AppInfo>())
        private set

    /** Dropped, with the arrangement still showing from here until the saved one comes back. */
    var settling by mutableStateOf(false)
        private set

    val editing: Boolean get() = lifted != null

    /** Whether the home screen should show [dock] and [grid] rather than the saved arrangement. */
    val active: Boolean get() = editing || settling

    fun start(app: AppInfo, dock: List<AppInfo>, grid: List<AppInfo>) {
        this.dock = dock
        this.grid = grid
        settling = false
        lifted = app
    }

    /** Ends editing and hands back the arrangement to save. */
    fun drop(): Pair<List<AppInfo>, List<AppInfo>> {
        lifted = null
        settling = true
        return dock to grid
    }

    /** The saved arrangement has caught up (or never will, say after an uninstall), so stop showing this one. */
    fun settled() {
        settling = false
    }

    fun matches(savedDock: List<AppInfo>, savedGrid: List<AppInfo>): Boolean =
        savedDock.map { it.packageName } == dock.map { it.packageName } &&
            savedGrid.map { it.packageName } == grid.map { it.packageName }

    /** Moves the lifted app one step toward [direction]. False when it's already against an edge. */
    fun move(direction: Direction): Boolean {
        val app = lifted ?: return false
        val inDock = dock.indexOf(app)
        if (inDock >= 0) return moveInDock(app, inDock, direction)
        val inGrid = grid.indexOf(app)
        return inGrid >= 0 && moveInGrid(app, inGrid, direction)
    }

    private fun moveInDock(app: AppInfo, index: Int, direction: Direction): Boolean = when (direction) {
        Direction.Left -> index > 0 && reorder(dock, index, index - 1) { dock = it }
        Direction.Right -> index < dock.lastIndex && reorder(dock, index, index + 1) { dock = it }
        Direction.Up -> false
        // Down into the grid, under the column it left.
        Direction.Down -> {
            dock = dock - app
            grid = grid.toMutableList().apply { add(index.coerceAtMost(size), app) }
            true
        }
    }

    private fun moveInGrid(app: AppInfo, index: Int, direction: Direction): Boolean {
        val columns = LumenDimens.TilesPerRow
        return when (direction) {
            Direction.Left -> index > 0 && reorder(grid, index, index - 1) { grid = it }
            Direction.Right -> index < grid.lastIndex && reorder(grid, index, index + 1) { grid = it }
            Direction.Up -> if (index >= columns) {
                reorder(grid, index, index - columns) { grid = it }
            } else {
                // Up from the first row into the dock, above the column it left.
                grid = grid - app
                dock = dock.toMutableList().apply { add(index.coerceAtMost(size), app) }
                true
            }
            Direction.Down -> {
                val target = (index + columns).coerceAtMost(grid.lastIndex)
                // Only onto a lower row: the last tile of the row below, if that row is short.
                target / columns > index / columns && reorder(grid, index, target) { grid = it }
            }
        }
    }

    private inline fun reorder(list: List<AppInfo>, from: Int, to: Int, set: (List<AppInfo>) -> Unit): Boolean {
        set(list.toMutableList().apply { add(to, removeAt(from)) })
        return true
    }

    enum class Direction { Left, Right, Up, Down }
}
