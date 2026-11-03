package app.lumen.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.openFeatured
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.liquidGlass
import app.lumen.core.designsystem.tvClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Top Shelf gone full screen, as Up does on tvOS: the dock slides away, and what the app
 * features fills the screen one program at a time. Left and Right change program, and it moves on
 * by itself after a while, as Apple's carousels do. Down or Back returns to the dock. The programs
 * on either side are fetched ahead with [preload], so moving on brings picture and text together.
 */
@Composable
internal fun FullScreenShelf(
    programs: List<ShelfProgram>,
    index: Int,
    onIndex: (Int) -> Unit,
    playFocus: FocusRequester,
    preload: suspend (ShelfProgram) -> Unit,
) {
    val sounds = LocalUiSounds.current
    val context = LocalContext.current
    val program = programs[index]
    LaunchedEffect(index, programs) {
        launch {
            preload(programs[(index + 1) % programs.size])
            preload(programs[(index - 1).mod(programs.size)])
        }
        delay(ADVANCE_MS)
        onIndex((index + 1) % programs.size)
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = LumenDimens.SafeHorizontal, bottom = LumenDimens.SafeVertical + 22.dp)
                .widthIn(max = TextMaxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = program,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) },
                label = "programText",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LumenText(shown.title, if (shown.title.length <= 24) LumenType.Display else LumenType.Title, maxLines = 2)
                    shown.detail?.let { LumenText(it, LumenType.Section, color = LumenColors.TextSecondary, maxLines = 1) }
                    shown.description?.let { LumenText(it, LumenType.Body, color = LumenColors.TextSecondary, maxLines = 3) }
                }
            }
            PlayButton(
                onClick = { openFeatured(context, program) },
                modifier = Modifier
                    .focusRequester(playFocus)
                    .onPreviewKeyEvent { event ->
                        val step = when (event.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            onIndex((index + step).mod(programs.size))
                            sounds?.move()
                        }
                        true
                    },
            )
        }
        if (programs.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = LumenDimens.SafeVertical),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                programs.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(if (i == index) LumenColors.TextPrimary else LumenColors.TextTertiary, CircleShape),
                    )
                }
            }
        }
    }
}

/** tvOS's capsule: glass at rest, white and a little larger with focus. */
@Composable
private fun PlayButton(onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "playScale",
    )
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(120), label = "playContent")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else LumenColors.GlassTintDim)
            .padding(start = 18.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(R.drawable.ic_play), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(content))
        LumenText("Play", LumenType.Headline, color = content)
    }
}

private const val ADVANCE_MS = 9_000L
private val TextMaxWidth = 560.dp
