package app.lumen.feature.home

import android.app.ActivityOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.UpNextItem
import app.lumen.core.apps.openUpNext
import app.lumen.core.designsystem.AppTile
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.SectionTitle

/**
 * The focused app's Watch Next row, in the stage just above the dock, like a tvOS app's
 * sectioned Top Shelf. Up from the dock steps into it; Back or Down steps out again. Tiles show
 * how far along each program is, and their title only while focused.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UpNextRow(items: List<UpNextItem>, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        SectionTitle("Up Next", inset = LumenDimens.SafeHorizontal)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer()
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back) return@onPreviewKeyEvent false
                    // Back to the dock, the way tvOS climbs out of a Top Shelf. Both halves of the
                    // press are taken, so the release doesn't also reach the screen's own Back.
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(FocusDirection.Down)
                    true
                },
            contentPadding = PaddingValues(horizontal = LumenDimens.SafeHorizontal, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(LumenDimens.TileSpacing),
        ) {
            items(items) { item -> UpNextTile(item) }
        }
    }
}

@Composable
private fun UpNextTile(item: UpNextItem) {
    val context = LocalContext.current
    val view = LocalView.current
    val art = remember(item.thumbnail) { item.thumbnail.asImageBitmap() }
    // Written on every layout pass, read only on click, so it's a plain holder, not state.
    val bounds = remember { arrayOf(Rect.Zero) }
    AppTile(
        label = item.title,
        art = art,
        onClick = {
            val r = bounds[0]
            // The program grows out of its tile, like an app from the dock.
            val zoom = ActivityOptions.makeScaleUpAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
            openUpNext(context, item, zoom.toBundle())
        },
        tileModifier = Modifier.onGloballyPositioned { bounds[0] = it.boundsInRoot() },
    )
}
