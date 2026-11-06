package app.lumen.feature.home

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.UpNextItem
import app.lumen.core.designsystem.LocalFocusIdle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val CONTINUE_RECHECK_MS = 20_000L
private const val SETTLE_TIMEOUT_MS = 1_000L
/** How long after [LocalFocusIdle] begins that the dock slides away: about 20 seconds without a key in all. */
private const val DOCK_REST_MS = 13_000L
/** How long each picture stays up while the dock rests: longer than in full screen, as nothing's asking to be read. */
private const val REST_CYCLE_MS = 12_000L
internal const val SETTINGS_FOCUS_KEY = "settings"

/** Rereads Continue Watching on coming back from an app, and again a little later, since apps can take a moment to publish what was just watched. */
@Composable
internal fun ContinueWatchingRefresh(viewModel: HomeViewModel, allApps: List<AppInfo>, removedPrograms: Map<String, String>) {
    val tvAppPackages = remember(allApps) { allApps.filter { it.isTvApp }.map { it.packageName } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tvAppPackages, removedPrograms) {
        lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.RESUMED) }
            .distinctUntilChanged()
            .collectLatest { resumed ->
                if (!resumed || tvAppPackages.isEmpty()) return@collectLatest
                viewModel.refreshContinueWatching(tvAppPackages)
                delay(CONTINUE_RECHECK_MS)
                viewModel.refreshContinueWatching(tvAppPackages)
            }
    }
}

/**
 * Where focus goes when the screen changes under it: at start, after an app moves or a program leaves a
 * row, into full screen, back to the dock, and on Home. Also opens Control Center when Home is held.
 */
@Composable
internal fun HomeFocusEffects(
    ui: HomeScreenState,
    state: HomeUiState,
    viewModel: HomeViewModel,
    shelfDock: List<HomeItem>,
    folderIsOpen: Boolean,
    continueWatching: List<UpNextItem>,
    homePresses: Flow<Unit>,
    controlCenterRequests: MutableSharedFlow<Unit>,
    hero: State<AppInfo?>,
    drop: () -> Unit,
) {
    // If the last program goes while focus is on it, focus needs somewhere to land.
    LaunchedEffect(continueWatching.isEmpty()) {
        if (continueWatching.isEmpty() && ui.continueHasFocus) runCatching { ui.firstFocus.requestFocus() }
    }
    LaunchedEffect(ui.refocus) {
        val target = ui.refocus ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        // The row is gone if that was its last program, which leaves its requester unattached.
        if (runCatching { target.requestFocus() }.isFailure) runCatching { ui.firstFocus.requestFocus() }
        ui.refocus = null
    }

    val editor = ui.editor
    // Focus follows the lifted app wherever it lands, even onto another row.
    LaunchedEffect(editor.lifted, editor.dock, editor.grid) {
        val item = editor.lifted ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { ui.focusFor(item.key).requestFocus() }
    }
    // Hand back to the saved arrangement once it matches what was dropped; give up after a moment
    // in case it never will (an app was uninstalled meanwhile, say).
    LaunchedEffect(editor.settling, shelfDock, state.grid) {
        if (!editor.settling) return@LaunchedEffect
        if (editor.matches(shelfDock, state.grid)) editor.settled() else {
            delay(SETTLE_TIMEOUT_MS)
            editor.settled()
        }
    }

    // Initial focus once tiles exist: back where the user left off, as on returning from Settings; otherwise the first tile.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (state.loading || initialFocusDone) return@LaunchedEffect
        withFrameNanos { }
        val returning = when (val key = viewModel.lastFocusKey) {
            null -> null
            SETTINGS_FOCUS_KEY -> ui.settingsFocus
            else -> ui.knownFocus(key)
        }
        val restored = returning != null && runCatching { returning.requestFocus() }.isSuccess
        initialFocusDone = restored || runCatching {
            if (state.allApps.isEmpty()) ui.settingsFocus.requestFocus() else ui.firstFocus.requestFocus()
        }.isSuccess
    }

    // The arrangement a moved app lands in arrives a moment later, so this tries again as it changes.
    // It waits for an open folder to close, whose window would otherwise take focus back.
    LaunchedEffect(ui.pendingFocus, shelfDock, state.grid, folderIsOpen) {
        val key = ui.pendingFocus?.takeUnless { folderIsOpen } ?: return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { ui.focusFor(key).requestFocus() }.isSuccess) ui.pendingFocus = null
    }

    // Home pressed while already home: back to the top, like tvOS. Home passes through Google TV's home
    // for a moment, and Android puts focus back where it was once Atrium's window has it again, so this
    // waits for that rather than having its own move undone.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(homePresses) {
        homePresses.collect {
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            withFrameNanos { }
            ui.homePressed(drop)
        }
    }

    // Home held down, through the Home Button service. Replayed to whichever home screen comes next, so it's cleared once seen.
    LaunchedEffect(controlCenterRequests) {
        controlCenterRequests.collect {
            ui.controlCenterOpen = true
            controlCenterRequests.resetReplayCache()
        }
    }

    // Focus moves to Play as soon as the full-screen Top Shelf exists.
    LaunchedEffect(ui.isFullScreen) {
        if (!ui.isFullScreen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { ui.playFocus.requestFocus() }
    }

    LaunchedEffect(ui.browsingBelow) {
        if (ui.browsingBelow) return@LaunchedEffect
        ui.listState.animateScrollToItem(0)
        val owed = ui.dockFocusOwed ?: return@LaunchedEffect
        ui.dockFocusOwed = null
        withFrameNanos { }
        when (owed) {
            DockFocus.FirstApp -> ui.focusFirstApp()
            DockFocus.LastApp -> ui.focusDock()
        }
    }

    // A program cycled to while resting belongs to that app only.
    LaunchedEffect(Unit) {
        snapshotFlow { hero.value?.packageName }.drop(1).collect { ui.restIndex = null }
    }
}

/**
 * The dock slides away once a Top Shelf has been left alone, as it does on tvOS: a little after the
 * remote goes quiet and only while [allowed]. The screen saver and every key end [LocalFocusIdle],
 * which ends this too. Left resting, the stage then moves through what the app features, as Apple's
 * previews do, passing over anything too soft to show alone, until the screen saver takes over.
 */
@Composable
internal fun DockRest(ui: HomeScreenState, allowed: Boolean, featured: List<ShelfProgram>, isSharp: suspend (ShelfProgram) -> Boolean) {
    val idle = LocalFocusIdle.current
    LaunchedEffect(idle, allowed) {
        ui.dockResting = false
        if (!idle || !allowed) return@LaunchedEffect
        delay(DOCK_REST_MS)
        ui.dockResting = true
    }
    LaunchedEffect(ui.dockResting, featured) {
        if (!ui.dockResting || featured.size < 2) return@LaunchedEffect
        while (true) {
            delay(REST_CYCLE_MS)
            val current = ui.restIndex ?: 0
            ui.restIndex = (1 until featured.size)
                .map { (current + it) % featured.size }
                .firstOrNull { isSharp(featured[it]) }
                ?: current
        }
    }
}
