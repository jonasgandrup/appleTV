package app.lumen.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.Clock
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.system.SystemActions
import kotlinx.coroutines.flow.Flow

/** Height of the Top Shelf row plus a peek of the grid below it. */
private val ShelfAreaHeight = 196.dp
private val CollapsedHeroHeight = 76.dp

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    homePresses: Flow<Unit>,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var focusedApp by remember { mutableStateOf<AppInfo?>(null) }
    var browsingBelow by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<AppInfo?>(null) }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    // The hero follows focus, falling back to the first pinned app (and dropping apps that were
    // hidden or uninstalled since they were focused).
    val hero = focusedApp?.let { f -> state.allApps.find { it.packageName == f.packageName } }
        ?: state.favorites.firstOrNull()
        ?: state.allApps.firstOrNull()

    val actions = remember(viewModel) {
        TileActions(
            onLaunch = viewModel::launch,
            onOptions = { optionsFor = it },
            onFocused = { focusedApp = it },
        )
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
            optionsFor = null
            browsingBelow = false
            listState.animateScrollToItem(0)
            runCatching { firstFocus.requestFocus() }
        }
    }

    LaunchedEffect(browsingBelow) {
        if (!browsingBelow) listState.animateScrollToItem(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight by animateDpAsState(
            targetValue = if (browsingBelow) CollapsedHeroHeight else maxHeight - ShelfAreaHeight,
            animationSpec = tween(500, easing = FastOutSlowInEasing),
            label = "heroHeight",
        )

        Backdrop(
            wallpaper = state.settings.wallpaper,
            art = if (state.settings.ambientBackdrop) hero?.art else null,
            dim = if (browsingBelow) 0.55f else 0f,
        )

        Column(Modifier.fillMaxSize()) {
            Hero(app = hero, visible = !browsingBelow, modifier = Modifier.fillMaxWidth().height(heroHeight))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                item(key = "shelf") {
                    if (state.favorites.isEmpty()) {
                        EmptyShelf()
                    } else {
                        AppRow(
                            apps = state.favorites,
                            actions = actions,
                            firstTileFocus = firstFocus,
                            modifier = Modifier.onFocusChanged { if (it.hasFocus) browsingBelow = false },
                        )
                    }
                }
                // One continuous grid below the shelf, ending with Settings, like tvOS.
                val gridRows = (state.others.map(GridTile::App) + GridTile.Settings).chunked(LumenDimens.TilesPerRow)
                itemsIndexed(gridRows, key = { i, _ -> "grid-$i" }) { index, row ->
                    AppGridRow(
                        tiles = row,
                        actions = actions,
                        onOpenSettings = onOpenSettings,
                        firstTileFocus = if (index == 0 && state.favorites.isEmpty()) firstFocus else null,
                        settingsFocus = if (index == gridRows.lastIndex) settingsFocus else null,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) browsingBelow = true },
                    )
                }
            }
        }

        if (state.settings.showClock) {
            Clock(Modifier.align(Alignment.TopEnd).padding(top = LumenDimens.SafeVertical, end = LumenDimens.SafeHorizontal))
        }

        optionsFor?.let { app ->
            AppOptionsDialog(
                app = app,
                favoriteIndex = state.favorites.indexOfFirst { it.packageName == app.packageName },
                favoriteCount = state.favorites.size,
                onDismiss = { optionsFor = null },
                options = AppOptions(
                    onOpen = { viewModel.launch(app) },
                    onToggleFavorite = { viewModel.toggleFavorite(app) },
                    onMove = { viewModel.moveFavorite(app, it) },
                    onHide = { viewModel.hide(app) },
                    onAppInfo = { SystemActions.openAppInfo(context, app.packageName) },
                ),
            )
        }
    }
}

@Composable
private fun EmptyShelf() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
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
