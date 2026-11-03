package app.lumen.feature.home

import android.app.ActivityOptions
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.UpNextItem
import app.lumen.core.apps.openUpNext
import app.lumen.core.designsystem.AppTile
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType

/**
 * A row of wide 16:9 program tiles under a small heading, running off the right edge when there are
 * more, modeled on Continue Watching in Apple's TV app. Tiles show how far along each program is,
 * and their title, episode and time left only while focused, after the name [appName] gives their
 * app when the row mixes apps. Holding select on a tile asks for [onOptions], with its bounds in the
 * window to anchor a menu to. [firstTile] takes focus when something enters the row for the first
 * time, or when a tile goes away.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UpNextRow(
    items: List<UpNextItem>,
    heading: String,
    firstTile: FocusRequester,
    onOptions: (UpNextItem, Rect) -> Unit,
    modifier: Modifier = Modifier,
    appName: (String) -> String? = { null },
) {
    Column(modifier) {
        LumenText(heading, LumenType.Section, Modifier.padding(start = LumenDimens.SafeHorizontal), color = LumenColors.TextSecondary)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                // Entering for the first time starts at the first tile, wherever focus came from.
                .focusRestorer { firstTile },
            contentPadding = PaddingValues(start = LumenDimens.SafeHorizontal, end = LumenDimens.SafeHorizontal, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
        ) {
            itemsIndexed(items) { index, item ->
                UpNextTile(item, appName(item.packageName), onOptions, if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
            }
        }
    }
}

/** A program tile with its title under it while focused, and under that where the user is in it. */
@Composable
private fun UpNextTile(item: UpNextItem, appName: String?, onOptions: (UpNextItem, Rect) -> Unit, focusModifier: Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val art = remember(item.thumbnail) { item.thumbnail.asImageBitmap() }
    // Written on every layout pass, read only on click, so it's a plain holder, not state.
    val bounds = remember { arrayOf(Rect.Zero) }
    var focused by remember { mutableStateOf(false) }
    // Timed like a tile's own label, so the text arrives with the tile's growth.
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "upNextLabel")
    Column(Modifier.width(LumenDimens.ProgramTileWidth)) {
        AppTile(
            label = item.title,
            art = art,
            onClick = {
                val r = bounds[0]
                // The program grows out of its tile, like an app from the dock.
                val zoom = ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
                openUpNext(context, item, zoom.toBundle())
            },
            onLongClick = { onOptions(item, bounds[0]) },
            width = LumenDimens.ProgramTileWidth,
            height = LumenDimens.ProgramTileHeight,
            showLabel = false,
            focusScale = PROGRAM_FOCUS_SCALE,
            idleFocusScale = PROGRAM_IDLE_FOCUS_SCALE,
            tileModifier = focusModifier
                .onFocusChanged { focused = it.isFocused }
                .onGloballyPositioned { bounds[0] = it.boundsInRoot() },
        )
        // Room for both lines is always kept, so the row doesn't jump as focus arrives. Clears the focused tile's growth.
        Column(Modifier.padding(top = 12.dp).height(ProgramLabelHeight).graphicsLayer { alpha = labelAlpha }) {
            LumenText(item.title, LumenType.Body, maxLines = 1)
            listOfNotNull(appName, item.detail).joinToString(" · ").ifEmpty { null }?.let {
                LumenText(it, LumenType.Caption, color = LumenColors.TextSecondary, maxLines = 1)
            }
        }
    }
}

private const val PROGRAM_FOCUS_SCALE = 1.08f
private const val PROGRAM_IDLE_FOCUS_SCALE = 1.11f
private val ProgramLabelHeight = 36.dp
