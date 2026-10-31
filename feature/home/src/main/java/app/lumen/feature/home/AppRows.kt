package app.lumen.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.AppTile
import app.lumen.core.designsystem.LumenDimens

/** Room above and below tiles so the focus scale and shadow aren't clipped. */
private val RowVerticalPadding = 16.dp

internal class TileActions(
    val onLaunch: (AppInfo) -> Unit,
    val onOptions: (AppInfo) -> Unit,
    val onFocused: (AppInfo) -> Unit,
)

/** A horizontally scrolling row that remembers its focused tile when you come back to it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppRow(
    apps: List<AppInfo>,
    actions: TileActions,
    modifier: Modifier = Modifier,
    firstTileFocus: FocusRequester? = null,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        contentPadding = PaddingValues(horizontal = LumenDimens.SafeHorizontal, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            LauncherTile(
                app = app,
                actions = actions,
                tileModifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
            )
        }
    }
}

/** One fixed row of the All Apps grid. */
@Composable
internal fun AppGridRow(
    apps: List<AppInfo>,
    actions: TileActions,
    modifier: Modifier = Modifier,
    firstTileFocus: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LumenDimens.SafeHorizontal, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
    ) {
        apps.forEachIndexed { index, app ->
            LauncherTile(
                app = app,
                actions = actions,
                tileModifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun LauncherTile(app: AppInfo, actions: TileActions, tileModifier: Modifier) {
    val banner = remember(app.banner) { app.banner?.asImageBitmap() }
    val icon = remember(app.icon) { app.icon.asImageBitmap() }
    AppTile(
        label = app.label,
        banner = banner,
        icon = icon,
        accent = Color(app.accentColor),
        onClick = { actions.onLaunch(app) },
        onLongClick = { actions.onOptions(app) },
        onFocused = { actions.onFocused(app) },
        tileModifier = tileModifier,
    )
}
