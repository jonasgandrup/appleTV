package app.lumen.core.designsystem

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LumenText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LumenColors.TextPrimary,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The D-pad direction that last moved focus between tiles, so the newly focused tile can carry
 * that motion. Read and cleared by the tile that gains focus; focus arriving from anything
 * other than a tile leaves it at zero, so that tile doesn't lean.
 */
private object TileMotion {
    var dx = 0f
    var dy = 0f
}

private const val TILT_DEGREES = 6f

/**
 * A tvOS-style app tile: lifts and grows on focus, leans into D-pad presses, catches a sweep of
 * light, and reveals its label underneath. [art] is the app's 16:9 tile artwork.
 */
@Composable
fun AppTile(
    label: String,
    art: ImageBitmap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tileModifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    // -1..1 per axis; positive leans right / down.
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)
    LaunchedEffect(focused) {
        if (focused) {
            tiltX.snapTo(TileMotion.dx)
            tiltY.snapTo(TileMotion.dy)
            TileMotion.dx = 0f
            TileMotion.dy = 0f
        }
        launch { tiltX.animateTo(0f, settle) }
        launch { tiltY.animateTo(0f, settle) }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val elevation by animateDpAsState(if (focused) 24.dp else 2.dp, tween(250), label = "tileElevation")
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "tileFocus")
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(LumenDimens.TileCorner)

    Column(modifier.width(LumenDimens.TileWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = tileModifier
                .size(LumenDimens.TileWidth, LumenDimens.TileHeight)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val (dx, dy) = when (event.key) {
                        Key.DirectionLeft -> -1f to 0f
                        Key.DirectionRight -> 1f to 0f
                        Key.DirectionUp -> 0f to -1f
                        Key.DirectionDown -> 0f to 1f
                        else -> return@onPreviewKeyEvent false
                    }
                    TileMotion.dx = dx
                    TileMotion.dy = dy
                    // Lean toward the press. If focus moves on, this tile settles back; at the
                    // end of a row the lean is the only feedback, like tvOS.
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        scope.launch {
                            launch { tiltX.animateTo(dx * 0.6f, tween(90)); tiltX.animateTo(0f, settle) }
                            launch { tiltY.animateTo(dy * 0.6f, tween(90)); tiltY.animateTo(0f, settle) }
                        }
                    }
                    false
                }
                .tvClickable(onLongClick = onLongClick, onClick = onClick)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // The side the remote points toward tips away from the viewer.
                    rotationY = tiltX.value * TILT_DEGREES
                    rotationX = -tiltY.value * TILT_DEGREES
                    cameraDistance = 16f * density
                }
                .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    // Top gloss while focused.
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.14f * focusAlpha), Color.Transparent),
                            endY = size.height * 0.6f,
                        ),
                    )
                    // A single diagonal sweep of light on focus.
                    val p = sheen.value
                    if (p > 0f && p < 1f) {
                        val x = size.width * (-0.4f + p * 1.8f)
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                                start = Offset(x - size.width * 0.25f, 0f),
                                end = Offset(x + size.width * 0.25f, size.height),
                            ),
                        )
                    }
                },
        ) {
            Image(
                bitmap = art,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
        Spacer(Modifier.height(14.dp))
        LumenText(
            text = label,
            style = LumenType.Caption,
            modifier = Modifier.graphicsLayer { alpha = focusAlpha },
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** A full-width list row in the style of tvOS Settings: flat at rest, white when focused. */
@Composable
fun MenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (focused) LumenColors.Focused else Color.Transparent, tween(160), label = "rowBg")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(160), label = "rowFg")
    val secondary = if (focused) LumenColors.OnFocused.copy(alpha = 0.6f) else LumenColors.TextSecondary
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.025f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "rowScale",
    )
    val elevation by animateDpAsState(if (focused) 16.dp else 0.dp, tween(200), label = "rowElevation")
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, shape)
            .background(background, shape)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            LumenText(title, LumenType.Headline, color = content, maxLines = 1)
            if (subtitle != null) LumenText(subtitle, LumenType.Caption, color = secondary, maxLines = 2)
        }
        if (value != null) LumenText(value, LumenType.Body, color = secondary, maxLines = 1)
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val text = remember(now, pattern) { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)) }
    LumenText(text, LumenType.Headline, modifier, color = LumenColors.TextPrimary)
}

/** Section heading inside scrolling screens. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    LumenText(
        text = text,
        style = LumenType.Section,
        color = LumenColors.TextSecondary,
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
    )
}
