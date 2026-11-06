package app.lumen.feature.home

import androidx.compose.runtime.getValue
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.ShelfProgram
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
/** Longer than the dock takes to settle after coming back from below, or from full screen. */
private const val UP_HINT_DELAY_MS = 600
private const val UP_HINT_FADE_MS = 300
private const val UP_HINT_LEAVE_MS = 80

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
            (ui.focusedRecommendation.value?.takeIf { it in items } ?: items.firstOrNull())?.thumbnail
        }
    }
    // What the focused app features, for the full-screen Top Shelf.
    val featured by remember { derivedStateOf { heroArt.value?.featured.orEmpty() } }
    val stageIsSharp by remember { derivedStateOf { heroArt.value?.isSharp == true } }
    val stageProgram = remember { derivedStateOf { (ui.fullScreen ?: ui.restIndex)?.let { featured.getOrNull(it) } } }

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
    HomeFocusEffects(
        ui = ui,
        state = state,
        viewModel = viewModel,
        shelfDock = shelfDock,
        folderIsOpen = openFolder != null,
        continueWatching = continueWatching,
        homePresses = homePresses,
        controlCenterRequests = controlCenterRequests,
        hero = hero,
        drop = ::dropLifted,
    )

    ScreensaverHost(
        timeoutMinutes = state.settings.screensaverMinutes,
        type = state.settings.screensaverType,
        showClock = state.settings.screensaverClock,
        holdOff = !state.settings.screensaverDuringMusic && device.nowPlaying?.isPlaying == true,
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
            // Left alone on sharp artwork, the dock slides away and the picture has the screen. Soft art, which most
            // apps publish, looks its worst alone, so the dock stays over it.
            DockRest(
                ui = ui,
                allowed = featured.isNotEmpty() && stageIsSharp && ui.dockHasFocus && !ui.browsingBelow && !ui.isFullScreen &&
                    !editor.active && !ui.overlayOpen,
                featured = featured,
                isSharp = viewModel::programIsSharp,
            )
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        ui.onKey(
                            event,
                            canOpenFullScreen = featured.isNotEmpty(),
                            onFirstApp = hero.value?.packageName == state.favorites.firstOrNull()?.packageName,
                            sounds = sounds,
                            drop = ::dropLifted,
                        )
                    },
            ) {
                val heroHeight = animateDpAsState(
                    targetValue = if (ui.browsingBelow) 0.dp else maxHeight - ShelfAreaHeight - LumenDimens.SafeVertical,
                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                    label = "heroHeight",
                )

                StageBackdrop(
                    hero = hero,
                    shelfArt = shelfArt,
                    wallpaper = state.settings.wallpaper,
                    ambient = state.settings.ambientBackdrop,
                    program = stageProgram,
                    programHasText = ui.isFullScreen,
                    slowFade = ui.dockResting,
                    loadProgram = viewModel::programBackdrop,
                    onProgramShown = { ui.shownProgram.value = it },
                    glow = stageGlow,
                    below = ui.browsingBelow,
                    frost = ui.liveFrost,
                )

                // The dock and grid slide off for the full-screen Top Shelf, staying in place for focus to come back to.
                val slidOff by animateFloatAsState(if (ui.isFullScreen || ui.dockResting) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "slidOff")
                val clockFade by animateFloatAsState(if (ui.dockResting) 0f else 1f, tween(450), label = "clockFade")
                val belowAmount by animateFloatAsState(if (ui.browsingBelow) 1f else 0f, tween(300), label = "belowAmount")
                Column(Modifier.fillMaxSize()) {
                    StageTitle(
                        hero = hero,
                        shelfArt = shelfArt,
                        visible = !ui.browsingBelow && !editor.editing && !ui.isFullScreen,
                        firstRowTile = ui.recommendationsFirstTile,
                        onProgramOptions = { item, bounds -> ui.programOptionsFor = ProgramOptions(item, bounds, removable = false) },
                        onRecommendationFocused = { ui.focusedRecommendation.value = it },
                        modifier = Modifier.fillMaxWidth().height(heroHeight),
                    )

                    LazyColumn(
                        state = ui.listState,
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
                                    firstTileFocus = ui.firstFocus,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            ui.dockHasFocus = it.hasFocus
                                            if (it.hasFocus) {
                                                ui.browsingBelow = false
                                                ui.fullScreen = null
                                            }
                                        }
                                        // Fades as it scrolls under the top edge, rather than leaving a strip of empty glass there.
                                        .graphicsLayer { alpha = ui.listState.visibleFraction(SHELF_KEY).let { ((it - 0.45f) / 0.35f).coerceIn(0f, 1f) } },
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
                                    headingAlpha = { belowAmount },
                                    modifier = Modifier
                                        .graphicsLayer { translationY = belowAmount * ContinueBrowseDrop.toPx() - (1f - belowAmount) * ContinuePeekLift.toPx() }
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
                                onOpenSettings = {
                                    viewModel.lastFocusKey = SETTINGS_FOCUS_KEY
                                    onOpenSettings()
                                },
                                lifted = editor.lifted,
                                firstTileFocus = if (dock.isEmpty()) ui.firstFocus else null,
                                settingsFocus = ui.settingsFocus,
                                modifier = Modifier.onFocusChanged { if (it.hasFocus) ui.browsingBelow = true },
                            )
                        }
                    }
                }

                if (ui.isFullScreen && featured.isNotEmpty()) {
                    FullScreenShelf(
                        programs = featured,
                        index = (ui.fullScreen ?: 0).coerceAtMost(featured.lastIndex),
                        shown = ui.shownProgram.value,
                        onIndex = { ui.fullScreen = it },
                        playFocus = ui.playFocus,
                        preload = viewModel::preloadProgram,
                        paused = ui.controlCenterOpen,
                    )
                }

                // Sits where the dock ends up, so it waits for the dock to get there: focus arrives well before it does, coming up from below.
                AnimatedVisibility(
                    visible = featured.isNotEmpty() && ui.dockHasFocus && !ui.browsingBelow && !ui.isFullScreen && !ui.dockResting && !editor.editing,
                    enter = fadeIn(tween(UP_HINT_FADE_MS, delayMillis = UP_HINT_DELAY_MS)),
                    exit = fadeOut(tween(UP_HINT_LEAVE_MS)),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ShelfAreaHeight + 5.dp),
                ) {
                    UpHint(Modifier)
                }

                // The top-right corner, on the stage only: the clock opens Control Center. Settings is a tile at the grid's end, as on tvOS.
                if (state.settings.showClock) {
                    AnimatedVisibility(
                        visible = !ui.browsingBelow,
                        enter = fadeIn(tween(300)),
                        exit = fadeOut(tween(150)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = LumenDimens.SafeVertical, end = LumenDimens.SafeHorizontal)
                            // Control Center shows the time in this same spot. Kept in place, so focus can come back to it.
                            .graphicsLayer { alpha = if (ui.controlCenterOpen) 0f else clockFade },
                    ) {
                        HomeClock(device, state.settings.profileName, profilePicture, onClick = { ui.controlCenterOpen = true })
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
    shelfArt: State<ShelfArt?>,
    wallpaper: Wallpaper,
    ambient: Boolean,
    program: State<ShelfProgram?>,
    programHasText: Boolean,
    slowFade: Boolean,
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
        programHasText = programHasText,
        slowFade = slowFade,
        loadProgram = loadProgram,
        onProgramShown = onProgramShown,
        glow = glow.value,
        below = below,
        frost = frost,
    )
}

/** tvOS's small arrow and words above the dock, saying there's more above. A shade under them keeps them visible on bright art. */
@Composable
private fun UpHint(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.size(width = 24.dp, height = 8.dp)) {
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
        LumenText("Press Up for full screen", UpHintText, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

private val UpHintText = LumenType.Caption.copy(
    fontSize = 10.5.sp,
    lineHeight = 13.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f),
)

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
