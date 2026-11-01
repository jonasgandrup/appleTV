package app.lumen.feature.home

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.AppTile
import app.lumen.core.designsystem.LumenDimens

/** Room above and below tiles so the focus scale and shadow aren't clipped. */
private val RowVerticalPadding = 16.dp

internal class TileActions(
    /** Opens the app; the bundle carries the zoom-from-tile launch animation. */
    val onLaunch: (AppInfo, Bundle?) -> Unit,
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

/** A tile in the home grid: an app, or the Settings tile that ends the grid, as on tvOS. */
internal sealed interface GridTile {
    data class App(val app: AppInfo) : GridTile
    data object Settings : GridTile
}

/** One fixed row of the home grid. */
@Composable
internal fun AppGridRow(
    tiles: List<GridTile>,
    actions: TileActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    firstTileFocus: FocusRequester? = null,
    settingsFocus: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LumenDimens.SafeHorizontal, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
    ) {
        tiles.forEachIndexed { index, tile ->
            val first = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier
            when (tile) {
                is GridTile.App -> LauncherTile(app = tile.app, actions = actions, tileModifier = first)
                GridTile.Settings -> SettingsTile(
                    onClick = onOpenSettings,
                    tileModifier = if (settingsFocus != null) first.focusRequester(settingsFocus) else first,
                )
            }
        }
    }
}

@Composable
private fun SettingsTile(onClick: () -> Unit, tileModifier: Modifier) {
    val context = LocalContext.current
    val art = remember { settingsArt(context) }
    AppTile(label = "Settings", art = art, onClick = onClick, tileModifier = tileModifier)
}

/** A gear on the graphite gradient of tvOS's Settings icon, at the same size as app tile art. */
private fun settingsArt(context: Context): ImageBitmap {
    val bitmap = Bitmap.createBitmap(SETTINGS_ART_WIDTH, SETTINGS_ART_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, SETTINGS_ART_HEIGHT.toFloat(), 0xFF8E8E93.toInt(), 0xFF545458.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, SETTINGS_ART_WIDTH.toFloat(), SETTINGS_ART_HEIGHT.toFloat(), paint)
    val gear = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_settings_gear))
    val size = SETTINGS_ART_HEIGHT / 2
    val left = (SETTINGS_ART_WIDTH - size) / 2
    val top = (SETTINGS_ART_HEIGHT - size) / 2
    gear.setBounds(left, top, left + size, top + size)
    gear.draw(canvas)
    return bitmap.asImageBitmap()
}

private const val SETTINGS_ART_WIDTH = 480
private const val SETTINGS_ART_HEIGHT = 270

@Composable
private fun LauncherTile(app: AppInfo, actions: TileActions, tileModifier: Modifier) {
    val art = remember(app.art) { app.art.asImageBitmap() }
    val view = LocalView.current
    // Written on every layout pass, read only on click, so it's a plain holder, not state.
    val bounds = remember { arrayOf(Rect.Zero) }
    AppTile(
        label = app.label,
        art = art,
        onClick = {
            val r = bounds[0]
            // The app's window grows out of the tile, as on tvOS.
            val zoom = ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
            actions.onLaunch(app, zoom.toBundle())
        },
        onLongClick = { actions.onOptions(app) },
        onFocused = { actions.onFocused(app) },
        tileModifier = tileModifier.onGloballyPositioned { bounds[0] = it.boundsInRoot() },
    )
}
