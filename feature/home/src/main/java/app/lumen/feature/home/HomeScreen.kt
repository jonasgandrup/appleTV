package app.lumen.feature.home

import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.UpNextItem
import app.lumen.core.designsystem.LiveFrost
import app.lumen.core.designsystem.LocalGlassBackdrop
import app.lumen.core.designsystem.LocalLiveFrost
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberFrostedWallpaper
import app.lumen.core.settings.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** The dock, plus a peek of what's below it, as on tvOS, where the tops of the next row hint at more. */
private val ShelfAreaHeight = LumenDimens.TileHeight + LumenDimens.DockInset * 2 + 36.dp
/** Lifts Continue Watching at rest, into the room its heading leaves while hidden, so its tiles' tops show. */
private val ContinuePeekLift = 15.dp
/** Drops it a little once browsing, for air between the dock and its heading. */
private val ContinueBrowseDrop = 10.dp
private const val CONTINUE_KEY = "continue"
private const val GRID_KEY = "grid"

/** How much of the item with [key] is inside the viewport, 0..1. Read it in a draw or layer block to stay out of composition. */
private fun LazyListState.visibleFraction(key: Any): Float {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
    if (item.size == 0) return 1f
    val top = maxOf(item.offset, layoutInfo.viewportStartOffset)
    val bottom = minOf(item.offset + item.size, layoutInfo.viewportEndOffset)
    return ((bottom - top).toFloat() / item.size).coerceIn(0f, 1f)
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    homePresses: Flow<Unit>,
    controlCenterRequests: MutableSharedFlow<Unit>,
    /** Given the Settings tile's bounds in the window, for Settings to grow out of. */
    onOpenSettings: (Rect) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sounds = LocalUiSounds.current
    val ui = rememberHomeScreenState()
    val editor = ui.editor
    val device = rememberDeviceSnapshot(context)
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val profilePicture by produceState<ImageBitmap?>(null) { value = viewModel.profilePicture()?.asImageBitmap() }

    // The hero follows focus, falling back to the first pinned app (and dropping apps that were
    // hidden or uninstalled since they were focused).
    val hero = remember {
        derivedStateOf {
            ui.focusedApp.value?.let { f -> state.allApps.find { it.packageName == f.packageName } }
                ?: state.favorites.firstOrNull()
                ?: state.allApps.firstOrNull()
        }
    }
    val shelfArt = rememberShelfArt(hero, state.settings.ambientBackdrop, state.settings.removedPrograms, viewModel)
    // The focused app's own art, once it has arrived; the last app's stays out of it until then.
    val heroArt = remember { derivedStateOf { shelfArt.value?.takeIf { it.packageName == hero.value?.packageName } } }
    // An app with only thumbnails lights the stage with the one in focus, or else its first.
    val stageGlow = remember {
        derivedStateOf {
            val items = heroArt.value?.recommendations.orEmpty()
            (ui.focusedRecommendation.value?.takeIf { it in items } ?: items.firstOrNull())?.glow
        }
    }

    // While an app is being moved, the editor's arrangement shows instead of the saved one.
    val dock = if (editor.active) editor.dock.filterIsInstance<HomeItem.App>().map { it.app } else state.favorites
    val gridItems = if (editor.active) editor.grid else state.grid
    // One continuous grid below the shelf, ending with Settings, like tvOS.
    val gridTiles = remember(gridItems) {
        gridItems.map { item ->
            when (item) {
                is HomeItem.App -> GridTile.App(item)
                is HomeItem.Folder -> GridTile.Folder(item)
            }
        } + GridTile.Settings
    }
    val shelfDock = remember(state.favorites) { state.favorites.map(HomeItem::App) }
    val openFolder = ui.openFolderId?.let { id -> state.grid.filterIsInstance<HomeItem.Folder>().firstOrNull { it.folder.id == id } }

    // Only the dock lights the stage, as only the top row has a Top Shelf on tvOS; the grid
    // keeps it steady instead of flashing a new color on every tile.
    val dockActions = remember(viewModel) {
        TileActions(
            onLaunch = viewModel::launch,
            onOptions = { app, bounds -> ui.optionsFor = app to bounds },
            onFocused = {
                ui.focusedApp.value = it
                viewModel.lastFocusKey = it.packageName
            },
            focusFor = ui::focusFor,
        )
    }
    val gridActions = remember(dockActions) {
        TileActions(
            onLaunch = dockActions.onLaunch,
            onOptions = dockActions.onOptions,
            onFocused = { viewModel.lastFocusKey = it.packageName },
            focusFor = dockActions.focusFor,
            onOpenFolder = { ui.openFolderId = it.folder.id },
            onFolderOptions = { folder, bounds -> ui.folderOptionsFor = folder to bounds },
            onFolderFocused = { viewModel.lastFocusKey = it.key },
        )
    }
    // Inside a folder, opening an app also closes the folder.
    val folderActions = remember(dockActions) {
        TileActions(
            onLaunch = { app, options ->
                ui.openFolderId = null
                dockActions.onLaunch(app, options)
            },
            onOptions = dockActions.onOptions,
            onFocused = {},
            focusFor = dockActions.focusFor,
        )
    }

    fun dropLifted() {
        val (newDock, newGrid) = editor.drop()
        viewModel.saveArrangement(newDock, newGrid)
    }

    ContinueWatchingRefresh(viewModel, state.allApps, state.settings.removedPrograms)
    ShelfPrefetch(viewModel, state.favorites, enabled = state.settings.ambientBackdrop)
    HomeFocusEffects(
        ui = ui,
        state = state,
        viewModel = viewModel,
        shelfDock = shelfDock,
        folderIsOpen = openFolder != null,
        continueWatching = continueWatching,
        homePresses = homePresses,
        controlCenterRequests = controlCenterRequests,
        drop = ::dropLifted,
    )

    ScreensaverHost(
        timeoutMinutes = state.settings.screensaverMinutes,
        type = state.settings.screensaverType,
        showClock = state.settings.screensaverClock,
        holdOff = !state.settings.screensaverDuringMusic && device.nowPlaying?.isPlaying == true,
        overlayOpen = ui.menuOpen || openFolder != null,
        onStart = ui::closeOverlays,
        nowPlaying = device.nowPlaying,
        onTypeChange = { viewModel.setScreensaverType(it) },
        loadPhotos = viewModel::screensaverPhotos,
        wake = homePresses,
        startNow = ui.screensaverRequests,
    ) {
        CompositionLocalProvider(
            LocalGlassBackdrop provides rememberFrostedWallpaper(state.settings.wallpaper),
            LocalLiveFrost provides ui.liveFrost,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        ui.onKey(
                            event,
                            onFirstApp = hero.value?.packageName == state.favorites.firstOrNull()?.packageName,
                            sounds = sounds,
                            drop = ::dropLifted,
                        )
                    },
            ) {
                // The stage above the dock, a fixed size: the rows slide up over it rather than it shrinking, so
                // nothing has to be laid out again on every frame of the move. Everything that moves with the rows
                // reads this one animation, so they all move as one.
                val stageHeight = maxHeight - ShelfAreaHeight - LumenDimens.SafeVertical
                val below = animateFloatAsState(if (ui.browsingBelow) 1f else 0f, BrowseSpec, label = "below")

                StageBackdrop(
                    hero = hero,
                    wallpaper = state.settings.wallpaper,
                    ambient = state.settings.ambientBackdrop,
                    glow = stageGlow,
                    below = ui.browsingBelow,
                    frost = ui.liveFrost,
                )

                Box(Modifier.fillMaxSize()) {
                    StageTitle(
                        hero = hero,
                        shelfArt = shelfArt,
                        visible = !ui.browsingBelow && !editor.editing,
                        firstRowTile = ui.recommendationsFirstTile,
                        onProgramOptions = { item, bounds -> ui.programOptionsFor = ProgramOptions(item, bounds, removable = false) },
                        onRecommendationFocused = { ui.focusedRecommendation.value = it },
                        modifier = Modifier.fillMaxWidth().height(stageHeight),
                    )

                    LazyColumn(
                        state = ui.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = (1f - below.value) * stageHeight.toPx() },
                        // Scrolling up to the screen's edge rather than clipping short of it.
                        contentPadding = PaddingValues(top = LumenDimens.SafeVertical, bottom = 48.dp),
                    ) {
                        item(key = SHELF_KEY) {
                            if (dock.isEmpty()) {
                                EmptyShelf()
                            } else {
                                AppRow(
                                    apps = dock,
                                    actions = dockActions,
                                    lifted = (editor.lifted as? HomeItem.App)?.app,
                                    firstTileFocus = ui.firstFocus,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            ui.dockHasFocus = it.hasFocus
                                            if (it.hasFocus) ui.browsingBelow = false
                                        }
                                        // Fades as it scrolls under the top edge, rather than leaving a strip of empty glass there.
                                        // Each piece faded in place, sparing the GPU an offscreen copy of the whole row on every frame of a scroll.
                                        .graphicsLayer {
                                            alpha = ui.listState.visibleFraction(SHELF_KEY).let { ((it - 0.45f) / 0.35f).coerceIn(0f, 1f) }
                                            compositingStrategy = CompositingStrategy.ModulateAlpha
                                        },
                                )
                            }
                        }
                        if (continueWatching.isNotEmpty() && !editor.active) {
                            item(key = CONTINUE_KEY) {
                                UpNextRow(
                                    items = continueWatching,
                                    heading = "Continue Watching",
                                    firstTile = ui.continueFirstTile,
                                    onOptions = { item, bounds -> ui.programOptionsFor = ProgramOptions(item, bounds, removable = true) },
                                    appName = { pkg -> state.allApps.find { it.packageName == pkg }?.label },
                                    headingAlpha = { below.value },
                                    modifier = Modifier
                                        .graphicsLayer { translationY = below.value * ContinueBrowseDrop.toPx() - (1f - below.value) * ContinuePeekLift.toPx() }
                                        .padding(top = 4.dp, bottom = 4.dp)
                                        .onFocusChanged {
                                            ui.continueHasFocus = it.hasFocus
                                            if (it.hasFocus) ui.browsingBelow = true
                                        },
                                )
                            }
                        }
                        item(key = GRID_KEY) {
                            AppGrid(
                                tiles = gridTiles,
                                actions = gridActions,
                                onOpenSettings = { tile ->
                                    viewModel.lastFocusKey = SETTINGS_FOCUS_KEY
                                    onOpenSettings(tile)
                                },
                                lifted = editor.lifted,
                                firstTileFocus = if (dock.isEmpty()) ui.firstFocus else null,
                                settingsFocus = ui.settingsFocus,
                                modifier = Modifier.onFocusChanged { if (it.hasFocus) ui.browsingBelow = true },
                            )
                        }
                    }
                }

                // The top-right corner, on the stage only: the clock opens Control Center. Settings is a tile at the grid's end, as on tvOS.
                if (state.settings.showClock) {
                    AnimatedVisibility(
                        visible = !ui.browsingBelow,
                        enter = EnterTransition.None,
                        exit = ExitTransition.None,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = LumenDimens.SafeVertical, end = LumenDimens.SafeHorizontal),
                    ) {
                        // Faded here rather than with fadeIn and fadeOut, which would copy it offscreen on every frame
                        // of the rows sliding, the busiest moment there is.
                        val clockAlpha = transition.animateFloat(
                            transitionSpec = { tween(if (targetState == EnterExitState.Visible) 300 else 150) },
                            label = "clockAlpha",
                        ) { if (it == EnterExitState.Visible) 1f else 0f }
                        Box(
                            Modifier.graphicsLayer {
                                // Control Center shows the time in this same spot. Kept in place, so focus can come back to it.
                                alpha = if (ui.controlCenterOpen) 0f else clockAlpha.value
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        ) {
                            HomeClock(device, state.settings.profileName, profilePicture, onClick = { ui.controlCenterOpen = true })
                        }
                    }
                }

                if (editor.editing) {
                    LumenText(
                        "Move the app with the remote, then press Select to save",
                        LumenType.Body,
                        // The stage has room at the top; over the grid, the dock is there instead.
                        modifier = Modifier
                            .align(if (ui.browsingBelow) Alignment.BottomCenter else Alignment.TopCenter)
                            .padding(vertical = LumenDimens.SafeVertical)
                            .liquidGlass(LumenDimens.CapsuleCorner, tint = LumenColors.GlassTintDim)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }

                HomeOverlays(ui, state, viewModel, device, profilePicture, openFolder, folderActions, shelfDock)
            }
        }
    }
}

/** [Backdrop] lit by the [hero]; reading it here keeps focus changes from recomposing the screen. */
@Composable
private fun StageBackdrop(
    hero: State<AppInfo?>,
    wallpaper: Wallpaper,
    ambient: Boolean,
    glow: State<Bitmap?>,
    below: Boolean,
    frost: LiveFrost,
) {
    Backdrop(
        wallpaper = wallpaper,
        app = hero.value.takeIf { ambient },
        glow = glow.value,
        below = below,
        frost = frost,
    )
}

@Composable
private fun StageTitle(
    hero: State<AppInfo?>,
    shelfArt: State<ShelfArt?>,
    visible: Boolean,
    firstRowTile: FocusRequester,
    onProgramOptions: (UpNextItem, Rect) -> Unit,
    onRecommendationFocused: (UpNextItem) -> Unit,
    modifier: Modifier,
) {
    val app = hero.value
    Hero(
        shelfArt = shelfArt.value?.takeIf { it.packageName == app?.packageName },
        visible = visible,
        firstRowTile = firstRowTile,
        onProgramOptions = onProgramOptions,
        onRecommendationFocused = onRecommendationFocused,
        modifier = modifier,
    )
}

@Composable
private fun EmptyShelf() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(horizontal = LumenDimens.SafeHorizontal),
        contentAlignment = Alignment.CenterStart,
    ) {
        LumenText(
            "Your top row is empty. Hold Select on an app below, then choose Move to and Top Row.",
            LumenType.Body,
            color = LumenColors.TextSecondary,
        )
    }
}
