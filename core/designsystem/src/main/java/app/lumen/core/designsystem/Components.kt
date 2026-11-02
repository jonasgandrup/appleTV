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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Scale of a focused tile, and of one that has held focus for a while. */
private const val FOCUS_SCALE = 1.12f
private const val IDLE_FOCUS_SCALE = 1.17f
private const val IDLE_SWAY = 0.3f
private const val IDLE_SWAY_MS = 2_600
private const val IDLE_SWAYS = 3

/** A tile lifted in Edit Home Screen: a little larger than focus, wiggling as on tvOS. */
private const val LIFTED_SCALE = 1.2f
private const val WIGGLE_DEGREES = 1.6f
private const val WIGGLE_MS = 130

private const val ROW_FOCUS_MS = 80

/**
 * True once the remote has gone quiet for a while. The focused tile then expands and gently sways,
 * as on tvOS.
 */
val LocalFocusIdle = compositionLocalOf { false }

/**
 * A tvOS-style app tile with a glass edge: lifts and grows on focus, leans into D-pad presses,
 * catches a sweep of light, and reveals its label underneath. [art] is the app's 16:9 tile artwork.
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
    /** The art lets the background through, so it gets no shadow, which would show through as a smudge. */
    translucent: Boolean = false,
    /** Picked up in Edit Home Screen. */
    lifted: Boolean = false,
    width: Dp = LumenDimens.TileWidth,
    height: Dp = LumenDimens.TileHeight,
    /** Off for tiles that say what they are some other way. */
    showLabel: Boolean = true,
    /** How much focus grows the tile: large tiles grow less, so they stay clear of their neighbors. */
    focusScale: Float = FOCUS_SCALE,
    idleFocusScale: Float = IDLE_FOCUS_SCALE,
) {
    val idle = LocalFocusIdle.current
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
    // Sways a few times and stops: an endless sway would keep the GPU busy for no one.
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(lifted) {
        if (!lifted) {
            wiggle.animateTo(0f, tween(WIGGLE_MS))
            return@LaunchedEffect
        }
        while (true) {
            wiggle.animateTo(WIGGLE_DEGREES, tween(WIGGLE_MS))
            wiggle.animateTo(-WIGGLE_DEGREES, tween(WIGGLE_MS))
        }
    }
    LaunchedEffect(focused, idle) {
        if (!focused || !idle || lifted) return@LaunchedEffect
        val slow = tween<Float>(IDLE_SWAY_MS, easing = FastOutSlowInEasing)
        tiltX.animateTo(IDLE_SWAY, slow)
        repeat(IDLE_SWAYS) {
            tiltX.animateTo(-IDLE_SWAY, slow)
            tiltX.animateTo(IDLE_SWAY, slow)
        }
        tiltX.animateTo(0f, slow)
    }
    val scale by animateFloatAsState(
        targetValue = when {
            lifted -> LIFTED_SCALE
            !focused -> 1f
            idle -> idleFocusScale
            else -> focusScale
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    // Only the focused tile casts a shadow: across a whole screen of tiles they add up on the Chromecast.
    // Read only inside the layer below, so animating it doesn't recompose the tile every frame.
    val elevation = animateDpAsState(
        when {
            translucent -> 0.dp
            lifted -> 36.dp
            focused -> 24.dp
            else -> 0.dp
        },
        tween(250),
        label = "tileElevation",
    )
    val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "tileFocus")
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(LumenDimens.TileCorner)

    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = tileModifier
                .size(width, height)
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
                    rotationZ = wiggle.value
                    cameraDistance = 16f * density
                    shadowElevation = elevation.value.toPx()
                    this.shape = shape
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    // A soft light above the glass while focused, sliding as the tile tilts.
                    if (focusAlpha > 0f) {
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.32f * focusAlpha), Color.Transparent),
                                center = Offset(
                                    size.width * (0.3f - tiltX.value * 0.35f),
                                    size.height * (-0.1f - tiltY.value * 0.35f),
                                ),
                                radius = size.width * 0.75f,
                            ),
                        )
                    }
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
                    drawGlassRim(LumenDimens.TileCorner, strength = 0.5f + 0.5f * focusAlpha)
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
        if (showLabel) {
            Spacer(Modifier.height(11.dp))
            LumenText(
                text = label,
                style = LumenType.Caption,
                modifier = Modifier.graphicsLayer { alpha = focusAlpha },
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A full-width list row in the style of tvOS Settings: flat at rest, white and lifted when focused.
 * The white switches almost at once, so moving down a list never shows two half-lit rows, and it
 * casts no shadow, which on glass reads as a smudge.
 */
@Composable
fun MenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    /** Leads to another page, shown as tvOS's chevron after the value. */
    chevron: Boolean = false,
    /** A selected choice in a list, shown as tvOS's checkmark. */
    checked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (focused) LumenColors.Focused else Color.Transparent, tween(ROW_FOCUS_MS), label = "rowBg")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(ROW_FOCUS_MS), label = "rowFg")
    val secondary = if (focused) LumenColors.OnFocused.copy(alpha = 0.6f) else LumenColors.TextSecondary
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.035f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "rowScale",
    )
    val shape = RoundedCornerShape(22.dp)

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
            .background(background, shape)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        LumenText(title, LumenType.Headline, Modifier.weight(1f), color = content, maxLines = 1)
        if (value != null) LumenText(value, LumenType.Body, color = secondary, maxLines = 1)
        if (checked) LumenText("✓", LumenType.Headline, color = content)
        if (chevron) LumenText("›", LumenType.Headline, color = secondary)
    }
}

/** The current time and date, updated on the minute. */
@Composable
fun rememberClockText(): Pair<String, String> {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = remember(now, pattern) { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault()).format(Date(now))
    }
    return time to date
}

/**
 * tvOS's profile picture: the initial of [name] on a colored disc, or a generic person when there's
 * no name. Android lets an app neither read the account's photo nor switch profiles.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val initial = name.trim().firstOrNull()?.uppercase()
    Box(modifier.size(size).background(AvatarColor, CircleShape), contentAlignment = Alignment.Center) {
        if (initial != null) {
            LumenText(initial, LumenType.Caption.copy(fontSize = (size.value * 0.56f).sp, lineHeight = (size.value * 0.7f).sp), color = LumenColors.TextPrimary)
        } else {
            Image(painterResource(R.drawable.ic_person), null, Modifier.size(size * 0.7f))
        }
    }
}

private val AvatarColor = Color(0xFF5E5CE6)

/** The small glass pill in the corner of the home screen, as on tvOS: the time and [profileName]'s picture. Press Up to reach it; it opens Control Center. */
@Composable
fun Clock(onClick: () -> Unit, modifier: Modifier = Modifier, profileName: String = "") {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "clockScale",
    )
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(160), label = "clockText")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else LumenColors.GlassTintDim)
            .padding(start = 12.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LumenText(rememberClockText().first, LumenType.Body, color = content)
        Avatar(profileName)
    }
}

/** Section heading inside scrolling screens. The default [inset] lines it up with [MenuRow] text. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, inset: Dp = 20.dp) {
    LumenText(
        text = text,
        style = LumenType.Section,
        color = LumenColors.TextSecondary,
        modifier = modifier.fillMaxWidth().padding(start = inset, top = 14.dp, bottom = 2.dp),
    )
}
