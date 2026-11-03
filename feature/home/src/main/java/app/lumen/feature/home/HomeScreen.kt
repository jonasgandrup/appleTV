package app.lumen.feature.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.UpNextItem
import app.lumen.core.designsystem.Clock
import app.lumen.core.designsystem.LiveFrost
import app.lumen.core.designsystem.LocalGlassBackdrop
import app.lumen.core.designsystem.LocalLiveFrost
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.TextEntryDialog
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.rememberFrostedWallpaper
import app.lumen.core.settings.AppFolder
import app.lumen.core.settings.Wallpaper
import app.lumen.core.system.HomeOverride
import app.lumen.core.system.DeviceStatus
import app.lumen.core.system.SleepTimer
import app.lumen.core.system.SystemActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The dock, plus a peek of what's below it, as on tvOS, where the tops of the next row hint at more. */
private val ShelfAreaHeight = LumenDimens.TileHeight + LumenDimens.DockInset * 2 + 36.dp
/** Lifts Continue Watching at rest, into the room its heading leaves while hidden, so its tiles' tops show. */
private val ContinuePeekLift = 15.dp
private const val SHELF_KEY = "shelf"
private const val CONTINUE_KEY = "continue"
private const val GRID_KEY = "grid"
private const val CONTINUE_RECHECK_MS = 20_000L
private const val SETTINGS_FOCUS_KEY = "settings"
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
    controlCenterRequests: MutableSharedFlow<Unit>,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sounds = LocalUiSounds.current

    // Held as State and read only by the stage, so moving along the dock recomposes the stage
    // rather than the whole screen.
    val focusedApp = remember { mutableStateOf<AppInfo?>(null) }
    var browsingBelow by rememberSaveable { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Pair<AppInfo, Rect>?>(null) }
    var folderOptionsFor by remember { mutableStateOf<Pair<HomeItem.Folder, Rect>?>(null) }
    var programOptionsFor by remember { mutableStateOf<ProgramOptions?>(null) }
    // Where focus goes after a program leaves a row, since it was on that program's tile.
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    val recommendationsFirstTile = remember { FocusRequester() }
    // Held as State and read only by the stage, like the focused app.
    val focusedRecommendation = remember { mutableStateOf<UpNextItem?>(null) }
    val shownProgram = remember { mutableStateOf<ShelfProgram?>(null) }
    val continueFirstTile = remember { FocusRequester() }
    var renaming by remember { mutableStateOf<AppFolder?>(null) }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    // Which of the app's featured programs fills the screen; null when the Top Shelf isn't full screen.
    var fullScreen by remember { mutableStateOf<Int?>(null) }
    // A tile that should take focus as soon as it exists: where an app just moved to, say.
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val playFocus = remember { FocusRequester() }
    val editor = remember { HomeEditor() }
    val tileFocus = remember { mutableMapOf<String, FocusRequester>() }
    var controlCenterOpen by remember { mutableStateOf(false) }
    // Read only when Back is pressed.
    var dockHasFocus by remember { mutableStateOf(false) }
    var backPressed by remember { mutableStateOf(false) }
    val screensaverRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val liveFrost = remember { LiveFrost() }
    val profilePicture by produceState<ImageBitmap?>(null) { value = viewModel.profilePicture()?.asImageBitmap() }

    // The hero follows focus, falling back to the first pinned app (and dropping apps that were
    // hidden or uninstalled since they were focused).
    val hero = remember {
        derivedStateOf {
            focusedApp.value?.let { f -> state.allApps.find { it.packageName == f.packageName } }
                ?: state.favorites.firstOrNull()
                ?: state.allApps.firstOrNull()
        }
    }
    val shelfArt = rememberShelfArt(hero, state.settings.ambientBackdrop, state.settings.removedPrograms, viewModel)
    // An app with only thumbnails lights the stage with the one in focus, or else its first.
    val stageGlow = remember {
        derivedStateOf {
            val items = shelfArt.value?.takeIf { it.packageName == hero.value?.packageName }?.recommendations.orEmpty()
            (focusedRecommendation.value?.takeIf { it in items } ?: items.firstOrNull())?.thumbnail
        }
    }
    val deviceStatus = remember { DeviceStatus(context) }
    val network by deviceStatus.network.collectAsStateWithLifecycle(null)
    val headphones by deviceStatus.headphones.collectAsStateWithLifecycle(null)
    val nowPlaying by deviceStatus.nowPlaying.collectAsStateWithLifecycle(null)
    val sleepEndsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    var continueHasFocus by remember { mutableStateOf(false) }
    // Coming back from an app is when this changes, and apps can take a moment to publish what was just watched.
    val tvAppPackages = remember(state.allApps) { state.allApps.filter { it.isTvApp }.map { it.packageName } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tvAppPackages, state.settings.removedPrograms) {
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
    // If the last program goes while focus is on it, focus needs somewhere to land.
    LaunchedEffect(continueWatching.isEmpty()) {
        if (continueWatching.isEmpty() && continueHasFocus) runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(refocus) {
        val target = refocus ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        // The row is gone if that was its last program, which leaves its requester unattached.
        if (runCatching { target.requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
        refocus = null
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
    val openFolder = openFolderId?.let { id -> state.grid.filterIsInstance<HomeItem.Folder>().firstOrNull { it.folder.id == id } }
    // What the focused app features, for the full-screen Top Shelf.
    val featured by remember {
        derivedStateOf { shelfArt.value?.takeIf { it.packageName == hero.value?.packageName }?.featured.orEmpty() }
    }
    val stageProgram = remember { derivedStateOf { fullScreen?.let { featured.getOrNull(it) } } }

    // Only the dock lights the stage, as only the top row has a Top Shelf on tvOS; the grid
    // keeps it steady instead of flashing a new color on every tile.
    val dockActions = remember(viewModel) {
        TileActions(
            onLaunch = viewModel::launch,
            onOptions = { app, bounds -> optionsFor = app to bounds },
            onFocused = {
                focusedApp.value = it
                viewModel.lastFocusKey = it.packageName
            },
            focusFor = { key -> tileFocus.getOrPut(key) { FocusRequester() } },
        )
    }
    val gridActions = remember(dockActions) {
        TileActions(
            onLaunch = dockActions.onLaunch,
            onOptions = dockActions.onOptions,
            onFocused = { viewModel.lastFocusKey = it.packageName },
            focusFor = dockActions.focusFor,
            onOpenFolder = { openFolderId = it.folder.id },
            onFolderOptions = { folder, bounds -> folderOptionsFor = folder to bounds },
            onFolderFocused = { viewModel.lastFocusKey = it.key },
        )
    }
    // Inside a folder, opening an app also closes the folder.
    val folderActions = remember(dockActions) {
        TileActions(
            onLaunch = { app, options ->
                openFolderId = null
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

    // Focus follows the lifted app wherever it lands, even onto another row.
    LaunchedEffect(editor.lifted, editor.dock, editor.grid) {
        val item = editor.lifted ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { dockActions.focusFor(item.key).requestFocus() }
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

    // Initial focus once tiles exist.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        if (state.loading || initialFocusDone) return@LaunchedEffect
        withFrameNanos { }
        // Back where the user left off, as on returning from Settings; otherwise the first tile.
        val returning = when (viewModel.lastFocusKey) {
            null -> null
            SETTINGS_FOCUS_KEY -> settingsFocus
            else -> tileFocus[viewModel.lastFocusKey]
        }
        val restored = returning != null && runCatching { returning.requestFocus() }.isSuccess
        initialFocusDone = restored || runCatching {
            if (state.allApps.isEmpty()) settingsFocus.requestFocus() else firstFocus.requestFocus()
        }.isSuccess
    }

    // The arrangement a moved app lands in arrives a moment later, so this tries again as it changes.
    // It waits for an open folder to close, whose window would otherwise take focus back.
    val folderIsOpen = openFolder != null
    LaunchedEffect(pendingFocus, shelfDock, state.grid, folderIsOpen) {
        val key = pendingFocus?.takeUnless { folderIsOpen } ?: return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { dockActions.focusFor(key).requestFocus() }.isSuccess) pendingFocus = null
    }

    // Home pressed while already home: back to the top, like tvOS.
    LaunchedEffect(homePresses) {
        homePresses.collect {
            if (editor.editing) dropLifted()
            optionsFor = null
            folderOptionsFor = null
            programOptionsFor = null
            renaming = null
            openFolderId = null
            controlCenterOpen = false
            fullScreen = null
            browsingBelow = false
            listState.animateScrollToItem(0)
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Home held down, through the Home Button service. Replayed to whichever home screen comes next, so it's cleared once seen.
    LaunchedEffect(controlCenterRequests) {
        controlCenterRequests.collect {
            controlCenterOpen = true
            controlCenterRequests.resetReplayCache()
        }
    }

    // Focus moves to Play as soon as the full-screen Top Shelf exists.
    val isFullScreen = fullScreen != null
    LaunchedEffect(isFullScreen) {
        if (!isFullScreen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    // Back climbs to the top row from anywhere, to the app last focused there, and from its first app
    // starts the screen saver, as on tvOS. While moving an app, it drops the app, keeping the new arrangement.
    fun back() {
        when {
            editor.editing -> dropLifted()
            !dockHasFocus -> {
                browsingBelow = false
                // The app last focused there can be gone by now, which leaves its requester unattached.
                val returned = focusedApp.value?.let { app -> runCatching { dockActions.focusFor(app.packageName).requestFocus() }.isSuccess } ?: false
                if (!returned) runCatching { firstFocus.requestFocus() }
            }
            hero.value?.packageName == state.favorites.firstOrNull()?.packageName -> screensaverRequests.tryEmit(Unit)
        }
    }

    LaunchedEffect(browsingBelow) {
        if (!browsingBelow) listState.animateScrollToItem(0)
    }

    ScreensaverHost(
        timeoutMinutes = state.settings.screensaverMinutes,
        type = state.settings.screensaverType,
        showClock = state.settings.screensaverClock,
        holdOff = !state.settings.screensaverDuringMusic && nowPlaying?.isPlaying == true,
        nowPlaying = nowPlaying,
        onTypeChange = { viewModel.setScreensaverType(it) },
        loadPhotos = viewModel::screensaverPhotos,
        wake = homePresses,
        startNow = screensaverRequests,
    ) {
        CompositionLocalProvider(
            LocalGlassBackdrop provides rememberFrostedWallpaper(state.settings.wallpaper),
            LocalLiveFrost provides liveFrost,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        // Taken here, ahead of Compose, which would otherwise treat Back as a focus move
                        // of its own and send focus somewhere unexpected. Acts on a press that began here,
                        // not on the release of one that closed an app or a menu.
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) backPressed = true
                            if (event.type == KeyEventType.KeyUp && backPressed) {
                                backPressed = false
                                back()
                            }
                            return@onPreviewKeyEvent true
                        }
                        // Up from the dock opens what's on the stage to full screen, as on tvOS. Apps with nothing to open let focus move on as usual.
                        if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown && !editor.editing && !isFullScreen) {
                            if (featured.isNotEmpty() && dockHasFocus) {
                                fullScreen = 0
                                sounds?.move()
                                return@onPreviewKeyEvent true
                            }
                        }
                        // In Edit Home Screen the D-pad moves the lifted app rather than focus, and Select drops it.
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
                    targetValue = if (browsingBelow) 0.dp else maxHeight - ShelfAreaHeight - LumenDimens.SafeVertical,
                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                    label = "heroHeight",
                )

                StageBackdrop(
                    hero = hero,
                    shelfArt = shelfArt,
                    wallpaper = state.settings.wallpaper,
                    ambient = state.settings.ambientBackdrop,
                    program = stageProgram,
                    loadProgram = viewModel::programBackdrop,
                    onProgramShown = { shownProgram.value = it },
                    glow = stageGlow,
                    below = browsingBelow,
                    frost = liveFrost,
                )

                // The dock and grid slide off for the full-screen Top Shelf, staying in place for focus to come back to.
                val slidOff by animateFloatAsState(if (isFullScreen) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "slidOff")
                val belowAmount by animateFloatAsState(if (browsingBelow) 1f else 0f, tween(300), label = "belowAmount")
                Column(Modifier.fillMaxSize()) {
                    StageTitle(
                        hero = hero,
                        shelfArt = shelfArt,
                        visible = !browsingBelow && !editor.editing && !isFullScreen,
                        firstRowTile = recommendationsFirstTile,
                        onProgramOptions = { item, bounds -> programOptionsFor = ProgramOptions(item, bounds, removable = false) },
                        onRecommendationFocused = { focusedRecommendation.value = it },
                        modifier = Modifier.fillMaxWidth().height(heroHeight),
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = slidOff * size.height
                                alpha = 1f - slidOff
                            },
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
                                    firstTileFocus = firstFocus,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            dockHasFocus = it.hasFocus
                                            if (it.hasFocus) {
                                                browsingBelow = false
                                                fullScreen = null
                                            }
                                        }
                                        // Fades as it scrolls under the top edge, rather than leaving a strip of empty glass there.
                                        .graphicsLayer { alpha = listState.visibleFraction(SHELF_KEY).let { ((it - 0.45f) / 0.35f).coerceIn(0f, 1f) } },
                                )
                            }
                        }
                        if (continueWatching.isNotEmpty() && !editor.active) {
                            item(key = CONTINUE_KEY) {
                                UpNextRow(
                                    items = continueWatching,
                                    heading = "Continue Watching",
                                    firstTile = continueFirstTile,
                                    onOptions = { item, bounds -> programOptionsFor = ProgramOptions(item, bounds, removable = true) },
                                    appName = { pkg -> state.allApps.find { it.packageName == pkg }?.label },
                                    headingAlpha = { belowAmount },
                                    modifier = Modifier
                                        .graphicsLayer { translationY = -(1f - belowAmount) * ContinuePeekLift.toPx() }
                                        .padding(top = 4.dp, bottom = 4.dp)
                                        .onFocusChanged {
                                            continueHasFocus = it.hasFocus
                                            if (it.hasFocus) browsingBelow = true
                                        },
                                )
                            }
                        }
                        item(key = GRID_KEY) {
                            AppGrid(
                                tiles = gridTiles,
                                actions = gridActions,
                                onOpenSettings = {
                                    viewModel.lastFocusKey = SETTINGS_FOCUS_KEY
                                    onOpenSettings()
                                },
                                lifted = editor.lifted,
                                firstTileFocus = if (dock.isEmpty()) firstFocus else null,
                                settingsFocus = settingsFocus,
                                modifier = Modifier.onFocusChanged { if (it.hasFocus) browsingBelow = true },
                            )
                        }
                    }
                }

                if (isFullScreen && featured.isNotEmpty()) {
                    FullScreenShelf(
                        programs = featured,
                        index = (fullScreen ?: 0).coerceAtMost(featured.lastIndex),
                        shown = shownProgram.value,
                        onIndex = { fullScreen = it },
                        playFocus = playFocus,
                        preload = viewModel::preloadProgram,
                        paused = controlCenterOpen,
                    )
                }

                if (featured.isNotEmpty() && dockHasFocus && !browsingBelow && !isFullScreen && !editor.editing) {
                    UpArrow(Modifier.align(Alignment.BottomCenter).padding(bottom = ShelfAreaHeight + 8.dp))
                }

                // The top-right corner, on the stage only: the clock opens Control Center. Settings is a tile at the grid's end, as on tvOS.
                if (state.settings.showClock) {
                    AnimatedVisibility(
                        visible = !browsingBelow,
                        enter = fadeIn(tween(300)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = LumenDimens.SafeVertical, end = LumenDimens.SafeHorizontal)
                            // Control Center shows the time in this same spot. Kept in place, so focus can come back to it.
                            .graphicsLayer { alpha = if (controlCenterOpen) 0f else 1f },
                    ) {
                        Clock(
                            onClick = { controlCenterOpen = true },
                            profileName = state.settings.profileName,
                            profilePicture = profilePicture,
                            status = { color ->
                                if (network?.hasInternet == false) StatusIcon("No internet", R.drawable.ic_status_no_internet, color)
                                if (sleepEndsAt != null) StatusIcon("Sleep Timer", R.drawable.ic_cc_timer, color)
                                if (nowPlaying?.isPlaying == true) StatusIcon("Now Playing", R.drawable.ic_status_music, color)
                                if (headphones != null) StatusIcon("Headphones", R.drawable.ic_status_headphones, color)
                            },
                        )
                    }
                }

                if (editor.editing) {
                    LumenText(
                        "Move the app with the remote, then press Select to save",
                        LumenType.Body,
                        // The stage has room at the top; over the grid, the dock is there instead.
                        modifier = Modifier
                            .align(if (browsingBelow) Alignment.BottomCenter else Alignment.TopCenter)
                            .padding(vertical = LumenDimens.SafeVertical)
                            .liquidGlass(LumenDimens.CapsuleCorner, tint = LumenColors.GlassTintDim)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }

                if (controlCenterOpen) {
                    ControlCenter(
                        profileName = state.settings.profileName,
                        profilePicture = profilePicture,
                        sleepReady = HomeOverride.isServiceEnabled(context),
                        status = ControlCenterStatus(
                            network = network,
                            headphones = headphones,
                            nowPlaying = nowPlaying,
                            nowPlayingApp = state.allApps.find { it.packageName == nowPlaying?.packageName }?.label,
                            sleepEndsAt = sleepEndsAt,
                            clickSounds = state.settings.navigationSounds,
                            device = deviceStatus,
                        ),
                        onClickSounds = viewModel::setClickSounds,
                        actions = ControlCenterActions(
                            // Sleep goes through the Home Button service; without it, this leads to where it's turned on.
                            onSleep = { if (!SystemActions.sleep()) SystemActions.openAccessibilitySettings(context) },
                            onScreensaver = { screensaverRequests.tryEmit(Unit) },
                            onNetwork = { SystemActions.openNetworkSettings(context) },
                            onSound = { SystemActions.openSoundSettings(context) },
                            onSettings = onOpenSettings,
                            onProfile = { SystemActions.openAccounts(context) },
                            onAndroidSettings = { SystemActions.openAndroidSettings(context) },
                            onSearch = { SystemActions.openSearch(context) },
                            onAccessibility = { SystemActions.openAccessibilitySettings(context) },
                        ),
                        onDismiss = { controlCenterOpen = false },
                    )
                }

                openFolder?.let { folder ->
                    FolderView(
                        folder = folder,
                        actions = folderActions,
                        onRename = { renaming = folder.folder },
                        onDismiss = {
                            openFolderId = null
                            pendingFocus = folder.key
                        },
                    )
                }

                optionsFor?.let { (app, bounds) ->
                    val ownFolder = state.folderOf(app)
                    val inTopRow = state.favorites.any { it.packageName == app.packageName }
                    AppOptionsDialog(
                        anchor = bounds,
                        onDismiss = { optionsFor = null },
                        options = AppOptions(
                            onEditHomeScreen = if (ownFolder != null) null else {
                                { editor.start(HomeItem.App(app), shelfDock, state.grid) }
                            },
                            moveTargets = buildList {
                                if (!inTopRow) add(MoveTarget.TopRow)
                                if (inTopRow || ownFolder != null) add(MoveTarget.HomeScreen)
                                add(MoveTarget.NewFolder)
                                state.grid.filterIsInstance<HomeItem.Folder>()
                                    .filter { it !== ownFolder }
                                    .forEach { add(MoveTarget.Folder(it.folder.id, it.folder.name)) }
                            },
                            // A new folder opens right away, as on tvOS, so it can be named.
                            onMove = { target ->
                                val created = viewModel.moveApp(app, target)
                                if (created != null) openFolderId = created
                                pendingFocus = when (target) {
                                    MoveTarget.TopRow, MoveTarget.HomeScreen -> app.packageName
                                    MoveTarget.NewFolder -> created?.let(::folderKey)
                                    is MoveTarget.Folder -> folderKey(target.id)
                                }
                            },
                            onHide = {
                                viewModel.hide(app)
                                pendingFocus = state.favorites.firstOrNull { it.packageName != app.packageName }?.packageName
                            },
                            onDelete = if (SystemActions.canUninstall(context, app.packageName)) {
                                { SystemActions.requestUninstall(context, app.packageName) }
                            } else {
                                null
                            },
                            onAppInfo = { SystemActions.openAppInfo(context, app.packageName) },
                        ),
                    )
                }

                programOptionsFor?.let { options ->
                    val app = state.allApps.find { it.packageName == options.item.packageName }
                    ProgramOptionsDialog(
                        anchor = options.bounds,
                        onRemove = if (options.removable) {
                            {
                                viewModel.removeProgram(options.item)
                                refocus = continueFirstTile
                            }
                        } else {
                            null
                        },
                        appLabel = app?.label,
                        onOpenApp = { app?.let { viewModel.launch(it) } },
                        onDismiss = { programOptionsFor = null },
                    )
                }

                folderOptionsFor?.let { (folder, bounds) ->
                    FolderOptionsDialog(
                        anchor = bounds,
                        onEditHomeScreen = { editor.start(folder, shelfDock, state.grid) },
                        onRename = { renaming = folder.folder },
                        onDismiss = { folderOptionsFor = null },
                    )
                }

                renaming?.let { folder ->
                    TextEntryDialog(
                        title = "Folder Name",
                        initial = folder.name,
                        onDone = { viewModel.renameFolder(folder.id, it) },
                        onDismiss = { renaming = null },
                    )
                }
            }
        }
    }
}

/** [Backdrop] lit by the [hero]; reading it here keeps focus changes from recomposing the screen. */
@Composable
private fun StageBackdrop(
    hero: State<AppInfo?>,
    shelfArt: State<ShelfArt?>,
    wallpaper: Wallpaper,
    ambient: Boolean,
    program: State<ShelfProgram?>,
    loadProgram: suspend (ShelfProgram) -> Bitmap?,
    onProgramShown: (ShelfProgram?) -> Unit,
    glow: State<Bitmap?>,
    below: Boolean,
    frost: LiveFrost,
) {
    Backdrop(
        wallpaper = wallpaper,
        app = hero.value.takeIf { ambient },
        shelfArt = shelfArt.value,
        program = program.value,
        loadProgram = loadProgram,
        onProgramShown = onProgramShown,
        glow = glow.value,
        below = below,
        frost = frost,
    )
}

/** One of tvOS's small status icons beside the clock. */
@Composable
private fun StatusIcon(description: String, @DrawableRes icon: Int, color: Color) {
    Image(painterResource(icon), description, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color))
}

/** A program held down in a row, for [ProgramOptionsDialog]. */
private class ProgramOptions(
    val item: UpNextItem,
    val bounds: Rect,
    val removable: Boolean,
)

/** tvOS's small arrow above the dock, saying there's more above. A shade under it keeps it visible on bright art. */
@Composable
private fun UpArrow(modifier: Modifier) {
    Canvas(modifier.size(width = 24.dp, height = 8.dp)) {
        val stroke = 2.5.dp.toPx()
        val top = Offset(size.width / 2, stroke / 2)
        val left = Offset(stroke / 2, size.height - stroke / 2)
        val right = Offset(size.width - stroke / 2, size.height - stroke / 2)
        val shade = Offset(0f, 1.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.4f), left + shade, top + shade, stroke, StrokeCap.Round)
        drawLine(Color.Black.copy(alpha = 0.4f), top + shade, right + shade, stroke, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f), left, top, stroke, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f), top, right, stroke, StrokeCap.Round)
    }
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
        app = app,
        shelfArt = shelfArt.value?.takeIf { it.packageName == app?.packageName },
        visible = visible,
        firstRowTile = firstRowTile,
        onProgramOptions = onProgramOptions,
        onRecommendationFocused = onRecommendationFocused,
        modifier = modifier,
    )
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
