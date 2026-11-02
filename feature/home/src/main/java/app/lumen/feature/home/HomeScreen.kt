package app.lumen.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.designsystem.Clock
import app.lumen.core.designsystem.LocalGlassBackdrop
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.rememberFrostedWallpaper
import app.lumen.core.settings.Wallpaper
import app.lumen.core.system.SystemActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** The dock, plus a peek of the grid below it. */
private val ShelfAreaHeight = 168.dp
private val CollapsedHeroHeight = 76.dp
private const val SHELF_KEY = "shelf"
private const val GRID_KEY = "grid"
private const val SETTLE_TIMEOUT_MS = 1_000L

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
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sounds = LocalUiSounds.current

    // Held as State and read only by the stage, so moving along the dock recomposes the stage
    // rather than the whole screen.
    val focusedApp = remember { mutableStateOf<AppInfo?>(null) }
    var browsingBelow by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Pair<AppInfo, Rect>?>(null) }
    val editor = remember { HomeEditor() }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    var controlCenterOpen by remember { mutableStateOf(false) }
    val screensaverRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    // The hero follows focus, falling back to the first pinned app (and dropping apps that were
    // hidden or uninstalled since they were focused).
    val hero = remember {
        derivedStateOf {
            focusedApp.value?.let { f -> state.allApps.find { it.packageName == f.packageName } }
                ?: state.favorites.firstOrNull()
                ?: state.allApps.firstOrNull()
        }
    }
    val shelfArt = rememberShelfArt(hero, state.settings.ambientBackdrop, viewModel::cachedShelfArt, viewModel::shelfArt)
    // While an app is being moved, the editor's arrangement shows instead of the saved one.
    val dock = if (editor.active) editor.dock else state.favorites
    val gridApps = if (editor.active) editor.grid else state.others
    // One continuous grid below the shelf, ending with Settings, like tvOS.
    val gridTiles = remember(gridApps) { gridApps.map(GridTile::App) + GridTile.Settings }

    // Only the dock lights the stage, as only the top row has a Top Shelf on tvOS; the grid
    // keeps it steady instead of flashing a new color on every tile.
    val dockActions = remember(viewModel) {
        TileActions(
            onLaunch = viewModel::launch,
            onOptions = { app, bounds -> optionsFor = app to bounds },
            onFocused = { focusedApp.value = it },
            focusFor = { tileFocus.getOrPut(it.packageName) { FocusRequester() } },
        )
    }
    val gridActions = remember(dockActions) {
        TileActions(onLaunch = dockActions.onLaunch, onOptions = dockActions.onOptions, onFocused = {}, focusFor = dockActions.focusFor)
    }

    fun dropLifted() {
        val (newDock, newGrid) = editor.drop()
        viewModel.saveArrangement(newDock, newGrid)
    }

    // Focus follows the lifted app wherever it lands, even onto another row.
    LaunchedEffect(editor.lifted, editor.dock, editor.grid) {
        val app = editor.lifted ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { dockActions.focusFor(app).requestFocus() }
    }
    // Hand back to the saved arrangement once it matches what was dropped; give up after a moment
    // in case it never will (an app was uninstalled meanwhile, say).
    LaunchedEffect(editor.settling, state.favorites, state.others) {
        if (!editor.settling) return@LaunchedEffect
        if (editor.matches(state.favorites, state.others)) editor.settled() else {
            delay(SETTLE_TIMEOUT_MS)
            editor.settled()
        }
    }

    // Initial focus once tiles exist.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (state.loading || initialFocusDone) return@LaunchedEffect
        withFrameNanos { }
        initialFocusDone = runCatching {
            if (state.allApps.isEmpty()) settingsFocus.requestFocus() else firstFocus.requestFocus()
        }.isSuccess
    }

    // Home pressed while already home: back to the top, like tvOS.
    LaunchedEffect(homePresses) {
        homePresses.collect {
            if (editor.editing) dropLifted()
            optionsFor = null
            controlCenterOpen = false
            browsingBelow = false
            listState.animateScrollToItem(0)
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Back climbs out of the grid to the dock; at the dock there's nowhere left to go.
    BackHandler(enabled = browsingBelow) {
        browsingBelow = false
        runCatching { firstFocus.requestFocus() }
    }

    // Back drops the lifted app where it is, keeping the new arrangement.
    BackHandler(enabled = editor.editing) { dropLifted() }

    LaunchedEffect(browsingBelow) {
        if (!browsingBelow) listState.animateScrollToItem(0)
    }

    ScreensaverHost(
        timeoutMinutes = state.settings.screensaverMinutes,
        wallpaper = state.settings.wallpaper,
        loadPhotos = viewModel::screensaverPhotos,
        wake = homePresses,
        startNow = screensaverRequests,
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides rememberFrostedWallpaper(state.settings.wallpaper)) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // In Edit Home Screen the D-pad moves the lifted app rather than focus, and Select drops it.
                    .onPreviewKeyEvent { event ->
                        if (!editor.editing) return@onPreviewKeyEvent false
                        val direction = when (event.key) {
                            Key.DirectionLeft -> HomeEditor.Direction.Left
                            Key.DirectionRight -> HomeEditor.Direction.Right
                            Key.DirectionUp -> HomeEditor.Direction.Up
                            Key.DirectionDown -> HomeEditor.Direction.Down
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyUp) dropLifted()
                                return@onPreviewKeyEvent true
                            }
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown && editor.move(direction)) sounds?.move()
                        true
                    },
            ) {
                val heroHeight = animateDpAsState(
                    targetValue = if (browsingBelow) CollapsedHeroHeight else maxHeight - ShelfAreaHeight,
                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                    label = "heroHeight",
                )

                StageBackdrop(
                    hero = hero,
                    shelfArt = shelfArt,
                    wallpaper = state.settings.wallpaper,
                    ambient = state.settings.ambientBackdrop,
                    dim = if (browsingBelow) 0.45f else 0f,
                )

                Column(Modifier.fillMaxSize()) {
                    StageTitle(hero = hero, shelfArt = shelfArt, visible = !browsingBelow, modifier = Modifier.fillMaxWidth().height(heroHeight))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        item(key = SHELF_KEY) {
                            if (dock.isEmpty()) {
                                EmptyShelf()
                            } else {
                                AppRow(
                                    apps = dock,
                                    actions = dockActions,
                                    lifted = editor.lifted,
                                    firstTileFocus = firstFocus,
                                    modifier = Modifier
                                        .onFocusChanged { if (it.hasFocus) browsingBelow = false }
                                        // Fades as it scrolls under the top edge, rather than leaving a strip of empty glass there.
                                        .graphicsLayer { alpha = listState.visibleFraction(SHELF_KEY).let { ((it - 0.45f) / 0.35f).coerceIn(0f, 1f) } },
                                )
                            }
                        }
                        item(key = GRID_KEY) {
                            AppGrid(
                                tiles = gridTiles,
                                actions = gridActions,
                                onOpenSettings = onOpenSettings,
                                lifted = editor.lifted,
                                firstTileFocus = if (dock.isEmpty()) firstFocus else null,
                                settingsFocus = settingsFocus,
                                modifier = Modifier.onFocusChanged { if (it.hasFocus) browsingBelow = true },
                            )
                        }
                    }
                }

                // The top-right corner: the clock opens Control Center, the gear opens Settings.
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = LumenDimens.SafeVertical, end = LumenDimens.SafeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.settings.showClock) Clock(onClick = { controlCenterOpen = true })
                    SettingsButton(onClick = onOpenSettings)
                }

                if (editor.editing) {
                    LumenText(
                        "Move the app with the remote, then press Select to drop it",
                        LumenType.Body,
                        color = LumenColors.TextSecondary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = LumenDimens.SafeVertical + 6.dp),
                    )
                }

                if (controlCenterOpen) {
                    ControlCenter(
                        actions = ControlCenterActions(
                            onScreensaver = { screensaverRequests.tryEmit(Unit) },
                            onSettings = onOpenSettings,
                            onAndroidSettings = { SystemActions.openAndroidSettings(context) },
                        ),
                        onDismiss = { controlCenterOpen = false },
                    )
                }

                optionsFor?.let { (app, bounds) ->
                    AppOptionsDialog(
                        app = app,
                        anchor = bounds,
                        isFavorite = state.favorites.any { it.packageName == app.packageName },
                        onDismiss = { optionsFor = null },
                        options = AppOptions(
                            onEditHomeScreen = { editor.start(app, state.favorites, state.others) },
                            onToggleFavorite = { viewModel.toggleFavorite(app) },
                            onHide = { viewModel.hide(app) },
                            onAppInfo = { SystemActions.openAppInfo(context, app.packageName) },
                        ),
                    )
                }
            }
        }
    }
}

/** [Backdrop] lit by the [hero]; reading it here keeps focus changes from recomposing the screen. */
@Composable
private fun StageBackdrop(hero: State<AppInfo?>, shelfArt: State<ShelfArt?>, wallpaper: Wallpaper, ambient: Boolean, dim: Float) {
    Backdrop(wallpaper = wallpaper, app = hero.value.takeIf { ambient }, shelfArt = shelfArt.value, dim = dim)
}

@Composable
private fun StageTitle(hero: State<AppInfo?>, shelfArt: State<ShelfArt?>, visible: Boolean, modifier: Modifier) {
    val app = hero.value
    Hero(app = app, shelfArt = shelfArt.value?.takeIf { it.packageName == app?.packageName }, visible = visible, modifier = modifier)
}

/** A height read during layout, so animating it relayouts without recomposing. */
private fun Modifier.height(height: State<Dp>) = layout { measurable, constraints ->
    val h = height.value.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, h) { placeable.place(0, 0) }
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
            "Your Top Shelf is empty. Hold select on any app below to pin it here.",
            LumenType.Body,
            color = LumenColors.TextSecondary,
        )
    }
}
