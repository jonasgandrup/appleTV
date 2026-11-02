package app.lumen.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lumen.core.designsystem.LumenDimens

/**
 * Edit Home Screen, as on tvOS: one app or folder is lifted and follows the remote through the dock
 * and the grid, the others making room, until Select drops it. Folders stay in the grid. The
 * arrangement lives here while it moves and is saved once, on drop, so each press answers instantly.
 */
@Stable
internal class HomeEditor {
    /** What's lifted; null when not editing. */
    var lifted by mutableStateOf<HomeItem?>(null)
        private set
    var dock by mutableStateOf(emptyList<HomeItem>())
        private set
    var grid by mutableStateOf(emptyList<HomeItem>())
        private set

    /** Dropped, with the arrangement still showing from here until the saved one comes back. */
    var settling by mutableStateOf(false)
        private set

    val editing: Boolean get() = lifted != null

    /** Whether the home screen should show [dock] and [grid] rather than the saved arrangement. */
    val active: Boolean get() = editing || settling

    fun start(item: HomeItem, dock: List<HomeItem>, grid: List<HomeItem>) {
        this.dock = dock
        this.grid = grid
        settling = false
        lifted = item
    }

    /** Ends editing and hands back the arrangement to save. */
    fun drop(): Pair<List<HomeItem>, List<HomeItem>> {
        lifted = null
        settling = true
        return dock to grid
    }

    /** The saved arrangement has caught up (or never will, say after an uninstall), so stop showing this one. */
    fun settled() {
        settling = false
    }

    fun matches(savedDock: List<HomeItem>, savedGrid: List<HomeItem>): Boolean =
        savedDock.map { it.key } == dock.map { it.key } && savedGrid.map { it.key } == grid.map { it.key }

    /** Moves the lifted item one step toward [direction]. False when it's already against an edge. */
    fun move(direction: Direction): Boolean {
        val item = lifted ?: return false
        val inDock = dock.indexOfFirst { it.key == item.key }
        if (inDock >= 0) return moveInDock(item, inDock, direction)
        val inGrid = grid.indexOfFirst { it.key == item.key }
        return inGrid >= 0 && moveInGrid(item, inGrid, direction)
    }

    private fun moveInDock(app: HomeItem, index: Int, direction: Direction): Boolean = when (direction) {
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

    private fun moveInGrid(app: HomeItem, index: Int, direction: Direction): Boolean {
        val columns = LumenDimens.TilesPerRow
        return when (direction) {
            Direction.Left -> index > 0 && reorder(grid, index, index - 1) { grid = it }
            Direction.Right -> index < grid.lastIndex && reorder(grid, index, index + 1) { grid = it }
            Direction.Up -> if (index >= columns) {
                reorder(grid, index, index - columns) { grid = it }
            } else if (app is HomeItem.Folder) {
                false
            } else {
                // Up from the first row into the dock, above the column it left. The top row has a
                // fixed size on tvOS, so a full one hands its last app down in exchange.
                val newDock = dock.toMutableList().apply { add(index.coerceAtMost(size), app) }
                val pushedDown = newDock.drop(columns)
                dock = newDock.take(columns)
                grid = pushedDown + (grid - app)
                true
            }
            Direction.Down -> {
                val target = (index + columns).coerceAtMost(grid.lastIndex)
                // Only onto a lower row: the last tile of the row below, if that row is short.
                target / columns > index / columns && reorder(grid, index, target) { grid = it }
            }
        }
    }

    private inline fun reorder(list: List<HomeItem>, from: Int, to: Int, set: (List<HomeItem>) -> Unit): Boolean {
        set(list.toMutableList().apply { add(to, removeAt(from)) })
        return true
    }

    enum class Direction { Left, Right, Up, Down }
}
