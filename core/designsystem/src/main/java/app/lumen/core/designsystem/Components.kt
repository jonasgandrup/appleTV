package app.lumen.core.designsystem

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
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
 * How far a tile slides toward a press that has nowhere to go, as at the end of a row, so the press
 * is still answered. When focus does move, nothing slides: one tile sinks as the next one grows, as
 * on tvOS, which sways a focused item only as a finger moves on the Siri Remote's touch surface.
 * Flat, as a 3D lean would make the Chromecast redraw the tile's rounded corners and shadow the slow
 * way on every frame.
 */
private val NUDGE = 5.dp

/** Scale of a focused tile. It stays at that size while focus rests on it, as on tvOS. */
private const val FOCUS_SCALE = 1.12f

/** A tile lifted in Edit Home Screen: a little larger than focus, wiggling as on tvOS. */
private const val LIFTED_SCALE = 1.2f
private const val WIGGLE_DEGREES = 1.6f
private const val WIGGLE_MS = 130

private const val ROW_FOCUS_MS = 80

/** Quick going down, so even a short click shows; focus's own spring brings it back up. */
private val PressSpec = tween<Float>(70, easing = FastOutSlowInEasing)
private val FocusSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 480f)

/**
 * The scale of a control that grows to [focusedScale] with focus and sinks back to its resting size
 * while select is held, the instant answer tvOS gives a click. Read it in a layer block to stay out
 * of composition.
 */
@Composable
fun animatePressableScale(focused: Boolean, pressed: Boolean, focusedScale: Float, label: String = "pressableScale"): State<Float> =
    animateFloatAsState(
        targetValue = if (focused && !pressed) focusedScale else 1f,
        animationSpec = if (pressed) PressSpec else FocusSpring,
        label = label,
    )

/**
 * A tvOS-style app tile with a glass edge: lifts and grows on focus, is nudged by a press that can't move focus, and
 * reveals its label underneath. [art] is the app's 16:9 tile artwork.
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
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // -1..1 per axis; positive nudges right / down.
    val nudgeX = remember { Animatable(0f) }
    val nudgeY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = spring<Float>(dampingRatio = 0.6f, stiffness = 260f)
    // Focus moving on in the middle of a nudge takes over from it.
    LaunchedEffect(focused) {
        if (focused) return@LaunchedEffect
        launch { nudgeX.animateTo(0f, settle) }
        launch { nudgeY.animateTo(0f, settle) }
    }
    // Wiggles for as long as the tile is lifted in Edit Home Screen, as on tvOS.
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
    val scale by animateFloatAsState(
        targetValue = when {
            lifted -> LIFTED_SCALE
            !focused || pressed -> 1f
            else -> focusScale
        },
        animationSpec = if (pressed) PressSpec else FocusSpring,
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
                    // Nudged toward the press only if focus is still here a frame later, with nowhere to go.
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        scope.launch {
                            withFrameNanos { }
                            if (!focused) return@launch
                            launch { nudgeX.animateTo(dx * 0.6f, tween(90)); nudgeX.animateTo(0f, settle) }
                            launch { nudgeY.animateTo(dy * 0.6f, tween(90)); nudgeY.animateTo(0f, settle) }
                        }
                    }
                    false
                }
                .tvClickable(onLongClick = onLongClick, onPressedChange = { pressed = it }, onClick = onClick)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = nudgeX.value * NUDGE.toPx()
                    translationY = nudgeY.value * NUDGE.toPx()
                    rotationZ = wiggle.value
                    shadowElevation = elevation.value.toPx()
                    this.shape = shape
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    // A soft light above the glass while focused, sliding as the tile is nudged.
                    if (focusAlpha > 0f) {
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.16f * focusAlpha), Color.Transparent),
                                center = Offset(
                                    size.width * (0.3f - nudgeX.value * 0.35f),
                                    size.height * (-0.1f - nudgeY.value * 0.35f),
                                ),
                                radius = size.width * 0.75f,
                            ),
                        )
                    }
                    drawGlassRim(LumenDimens.TileCorner, strength = 0.2f + 0.7f * focusAlpha)
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
                modifier = Modifier.graphicsLayer {
                    alpha = focusAlpha
                    // Faded in place: a layer of its own would cost an offscreen pass on every frame of the fade.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
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
    var pressed by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (focused) LumenColors.Focused else Color.Transparent, tween(ROW_FOCUS_MS), label = "rowBg")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(ROW_FOCUS_MS), label = "rowFg")
    val secondary = if (focused) LumenColors.OnFocused.copy(alpha = 0.6f) else LumenColors.TextSecondary
    val scale by animatePressableScale(focused, pressed, 1.02f, "rowScale")
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onPressedChange = { pressed = it }, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(background, shape)
            .padding(horizontal = 20.dp, vertical = 8.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        LumenText(title, LumenType.Row, Modifier.weight(1f), color = content, maxLines = 1)
        if (value != null) LumenText(value, LumenType.Row, color = secondary, maxLines = 1)
        if (checked) Image(painterResource(R.drawable.ic_row_check), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(content))
        if (chevron) Image(painterResource(R.drawable.ic_row_chevron), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(secondary))
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
 * tvOS's profile picture: [picture] when the user has added one, else the initial of [name], else a
 * generic person. The signed-in Google account's photo isn't available to other apps.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, picture: ImageBitmap? = null, size: Dp = 22.dp) {
    val initial = name.trim().firstOrNull()?.uppercase()
    Box(modifier.size(size).clip(CircleShape).background(AvatarColor), contentAlignment = Alignment.Center) {
        if (picture != null) {
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        } else if (initial != null) {
            LumenText(initial, LumenType.Caption.copy(fontSize = (size.value * 0.56f).sp, lineHeight = (size.value * 0.7f).sp), color = LumenColors.TextPrimary)
        } else {
            Image(painterResource(R.drawable.ic_person), null, Modifier.size(size * 0.7f))
        }
    }
}

private val AvatarColor = Color(0xFF636366)

/**
 * The small glass pill in the corner of the home screen, as on tvOS: the time, and the user's
 * picture once there's anything personal to show. Press Up to reach it; it opens Control Center.
 */
@Composable
fun Clock(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    profileName: String = "",
    profilePicture: ImageBitmap? = null,
    /** Status icons before the time, as on tvOS, given the color to draw them in. */
    status: @Composable (Color) -> Unit = {},
) {
    val showAvatar = profilePicture != null || profileName.isNotBlank()
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animatePressableScale(focused, pressed, 1.12f, "clockScale")
    val content by animateColorAsState(if (focused) LumenColors.OnFocused else LumenColors.TextPrimary, tween(160), label = "clockText")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onPressedChange = { pressed = it }, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(LumenDimens.CapsuleCorner, tint = if (focused) LumenColors.GlassFocused else LumenColors.GlassTintDim)
            .padding(start = 12.dp, end = if (showAvatar) 5.dp else 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        status(content)
        LumenText(rememberClockText().first, LumenType.Body, color = content)
        if (showAvatar) Avatar(profileName, picture = profilePicture)
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
