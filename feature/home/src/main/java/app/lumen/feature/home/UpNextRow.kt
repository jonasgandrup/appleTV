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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
 * The focused app's Watch Next row at the foot of the stage, modeled on Continue Watching in
 * Apple's TV app: wide 16:9 tiles under a small heading, running off the right edge when there are
 * more. Up from the dock steps into it; Back or Down steps out again. Tiles show how far along
 * each program is, and their title, episode and time left only while focused.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UpNextRow(items: List<UpNextItem>, modifier: Modifier = Modifier) {
    Column(modifier) {
        LumenText(
            "Up Next",
            LumenType.Section,
            Modifier.padding(start = LumenDimens.SafeHorizontal),
            color = LumenColors.TextSecondary,
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(),
            contentPadding = PaddingValues(start = LumenDimens.SafeHorizontal, end = LumenDimens.SafeHorizontal, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
        ) {
            items(items) { item -> UpNextTile(item) }
        }
    }
}

/** A program tile with its title under it while focused, and under that where the user is in it. */
@Composable
private fun UpNextTile(item: UpNextItem) {
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
            width = LumenDimens.ProgramTileWidth,
            height = LumenDimens.ProgramTileHeight,
            showLabel = false,
            focusScale = PROGRAM_FOCUS_SCALE,
            idleFocusScale = PROGRAM_IDLE_FOCUS_SCALE,
            tileModifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .onGloballyPositioned { bounds[0] = it.boundsInRoot() },
        )
        // Room for both lines is always kept, so the row doesn't jump as focus arrives. Clears the focused tile's growth.
        Column(Modifier.padding(top = 12.dp).height(ProgramLabelHeight).graphicsLayer { alpha = labelAlpha }) {
            LumenText(item.title, LumenType.Body, maxLines = 1)
            item.detail?.let { LumenText(it, LumenType.Caption, color = LumenColors.TextSecondary, maxLines = 1) }
        }
    }
}

private const val PROGRAM_FOCUS_SCALE = 1.08f
private const val PROGRAM_IDLE_FOCUS_SCALE = 1.11f
private val ProgramLabelHeight = 36.dp
