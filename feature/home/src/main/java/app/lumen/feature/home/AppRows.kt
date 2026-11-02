package app.lumen.feature.home

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.core.content.ContextCompat
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.AppTile
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.liquidGlass
import kotlinx.coroutines.launch

/** Room above and below grid tiles for the focus scale and label, keeping tvOS's 50 dp between rows. */
private val RowVerticalPadding = 12.dp

internal class TileActions(
    /** Opens the app; the bundle carries the zoom-from-tile launch animation. */
    val onLaunch: (AppInfo, Bundle?) -> Unit,
    /** Hold on a tile; [Rect] is the tile's bounds in the window, for anchoring the menu beside it. */
    val onOptions: (AppInfo, Rect) -> Unit,
    val onFocused: (AppInfo) -> Unit,
    /** One requester per app, so focus can follow an app that moved in Edit Home Screen. */
    val focusFor: (AppInfo) -> FocusRequester,
)

/** How far the dock's glass reaches past the tiles on each side. */
private val ShelfGlassOutset = 20.dp

/**
 * The dock: a horizontally scrolling row on a sheet of dim glass, remembering its focused tile
 * when you come back to it. The tiles stay aligned with the grid below, and slide aside when an
 * app is moved among them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppRow(
    apps: List<AppInfo>,
    actions: TileActions,
    modifier: Modifier = Modifier,
    lifted: AppInfo? = null,
    firstTileFocus: FocusRequester? = null,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LumenDimens.SafeHorizontal - ShelfGlassOutset)
            .liquidGlass(LumenDimens.GlassCorner, tint = LumenColors.GlassTintDim)
            .focusRestorer(),
        contentPadding = PaddingValues(start = ShelfGlassOutset, end = ShelfGlassOutset, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            LauncherTile(
                app = app,
                actions = actions,
                lifted = app.packageName == lifted?.packageName,
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = TileSlide),
                tileModifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
            )
        }
    }
}

/** A tile in the home grid: an app, or the Settings tile that ends the grid, as on tvOS. */
internal sealed interface GridTile {
    val key: Any

    data class App(val app: AppInfo) : GridTile {
        override val key get() = app.packageName
    }

    data object Settings : GridTile {
        override val key get() = "settings"
    }
}

/**
 * The grid below the dock, in rows of [LumenDimens.TilesPerRow]. One layout rather than a row per
 * line, so a tile keeps its identity when it moves to another line and can slide there.
 */
@Composable
internal fun AppGrid(
    tiles: List<GridTile>,
    actions: TileActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: AppInfo? = null,
    firstTileFocus: FocusRequester? = null,
    settingsFocus: FocusRequester? = null,
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LumenDimens.SafeHorizontal, vertical = RowVerticalPadding),
        content = {
            tiles.forEachIndexed { index, tile ->
                key(tile.key) {
                    val first = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier
                    when (tile) {
                        is GridTile.App -> LauncherTile(
                            app = tile.app,
                            actions = actions,
                            lifted = tile.app.packageName == lifted?.packageName,
                            modifier = Modifier.animatePlacement(),
                            tileModifier = first,
                        )
                        GridTile.Settings -> SettingsTile(
                            onClick = onOpenSettings,
                            modifier = Modifier.animatePlacement(),
                            tileModifier = if (settingsFocus != null) first.focusRequester(settingsFocus) else first,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val columns = LumenDimens.TilesPerRow
        val step = (LumenDimens.TileWidth + LumenDimens.TileSpacing).roundToPx()
        val rowGap = (RowVerticalPadding * 2).roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val cellHeight = placeables.maxOfOrNull { it.height } ?: 0
        val rows = (placeables.size + columns - 1) / columns
        val height = (rows * cellHeight + (rows - 1) * rowGap).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place((i % columns) * step, (i / columns) * (cellHeight + rowGap))
            }
        }
    }
}

private val TileSlide = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

/**
 * Slides this element from where it was last placed to where it's placed now, so tiles glide into
 * their new spots when the grid is rearranged instead of jumping.
 */
private fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val offset = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    this
        .onPlaced { coordinates ->
            val placed = coordinates.positionInParent().round()
            val previous = target
            target = placed
            // Start from where it was showing: its old place plus whatever slide was in progress.
            if (previous != null && previous != placed) {
                scope.launch {
                    offset.snapTo(previous - placed + offset.value)
                    offset.animateTo(IntOffset.Zero, TileSlide)
                }
            }
        }
        .offset { offset.value }
}

@Composable
private fun SettingsTile(onClick: () -> Unit, modifier: Modifier, tileModifier: Modifier) {
    val context = LocalContext.current
    val art = remember { settingsArt(context) }
    AppTile(label = "Settings", art = art, onClick = onClick, modifier = modifier, tileModifier = tileModifier, translucent = true)
}

/** A gear on a pane of tinted glass, at the same size as app tile art. */
private fun settingsArt(context: Context): ImageBitmap {
    val bitmap = Bitmap.createBitmap(SETTINGS_ART_WIDTH, SETTINGS_ART_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, SETTINGS_ART_HEIGHT.toFloat(), 0x66FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP)
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
private fun LauncherTile(app: AppInfo, actions: TileActions, lifted: Boolean, modifier: Modifier, tileModifier: Modifier) {
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
        onLongClick = { actions.onOptions(app, bounds[0]) },
        onFocused = { actions.onFocused(app) },
        lifted = lifted,
        modifier = modifier,
        tileModifier = tileModifier
            .focusRequester(actions.focusFor(app))
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() },
    )
}
