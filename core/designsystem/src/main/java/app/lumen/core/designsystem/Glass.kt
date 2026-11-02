package app.lumen.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Liquid glass, tuned for the Chromecast's GPU: a live blur of the screen is too heavy while tiles
 * animate, so nothing here blurs per frame. Glass gets its frost from images blurred once on the
 * CPU, and its liquid look from what's drawn on top: a lit rim, a soft highlight and a light bounce
 * along the bottom edge.
 */

/**
 * A blurred copy of what's behind glass, stretched over the whole window. Glass draws its own
 * slice of it, so sharp content behind the glass turns to frost. Null when the background is
 * already smooth, like a gradient, and the glass's tint is enough.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<ImageBitmap?> { null }

/** A floating sheet of glass behind this element's content. Draws outside its bounds for its shadow. */
fun Modifier.liquidGlass(corner: Dp, tint: Color = LumenColors.GlassTint): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var window by remember { mutableStateOf(IntSize.Zero) }

    this
        .onGloballyPositioned {
            origin = it.positionInRoot()
            window = it.findRootCoordinates().size
        }
        .drawWithCache {
            val radius = min(corner.toPx(), size.minDimension / 2)
            val outline = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(radius))) }
            val highlight = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.1f), Color.Transparent),
                center = Offset(size.width * 0.15f, 0f),
                radius = size.width * 0.5f,
            )
            // The tint, plus a light bounce along the bottom edge: one pass over the glass, not two.
            val fill = Brush.verticalGradient(
                0.6f to tint,
                1f to Color.White.copy(alpha = 0.07f).compositeOver(tint),
            )
            onDrawWithContent {
                drawGlassShadow(radius)
                if (backdrop != null && window != IntSize.Zero) {
                    clipPath(outline) {
                        val scaleX = backdrop.width.toFloat() / window.width
                        val scaleY = backdrop.height.toFloat() / window.height
                        drawImage(
                            backdrop,
                            srcOffset = IntOffset((origin.x * scaleX).roundToInt(), (origin.y * scaleY).roundToInt()),
                            srcSize = IntSize((size.width * scaleX).roundToInt(), (size.height * scaleY).roundToInt()),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                        // The window's scrims don't reach the frost, so it would glow without this.
                        drawRect(Color.Black.copy(alpha = 0.3f))
                    }
                }
                drawRoundRect(fill, cornerRadius = CornerRadius(radius))
                drawRoundRect(highlight, cornerRadius = CornerRadius(radius))
                drawGlassRim(corner)
                drawContent()
            }
        }
}

/**
 * The lit edge that makes a shape read as glass: brightest where the light lands at the top left,
 * catching again at the bottom right. Drawn just inside the bounds so a clip can't cut it.
 */
fun DrawScope.drawGlassRim(corner: Dp, strength: Float = 1f) {
    val width = 1.dp.toPx()
    val radius = min(corner.toPx(), size.minDimension / 2)
    drawRoundRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.5f * strength),
            0.35f to Color.White.copy(alpha = 0.1f * strength),
            0.65f to Color.White.copy(alpha = 0.05f * strength),
            1f to Color.White.copy(alpha = 0.26f * strength),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(radius - width / 2),
        style = Stroke(width),
    )
}

/**
 * A soft shadow around the glass. Elevation shadows show through translucent shapes as a dark
 * smudge, so this only paints outside the outline.
 */
private fun DrawScope.drawGlassShadow(radius: Float) {
    val step = 3.dp.toPx()
    repeat(SHADOW_RINGS) { ring ->
        val spread = step * (ring + 0.5f)
        drawRoundRect(
            Color.Black.copy(alpha = SHADOW_ALPHA * (SHADOW_RINGS - ring) / SHADOW_RINGS),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(radius + spread),
            style = Stroke(step),
        )
    }
}

private const val SHADOW_RINGS = 5
private const val SHADOW_ALPHA = 0.09f

/**
 * A frosted copy of the current window, for glass that floats over a whole screen. Captured once,
 * so call it from the window underneath the glass rather than from inside a dialog. Null until the
 * capture is ready, and stays null if the window can't be captured.
 */
@Composable
fun rememberFrostedWindow(): ImageBitmap? {
    val view = LocalView.current
    val image by produceState<ImageBitmap?>(null, view) {
        val window = view.context.findActivity()?.window ?: return@produceState
        val capture = Bitmap.createBitmap(FROST_WIDTH, FROST_HEIGHT, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { continuation ->
            runCatching {
                PixelCopy.request(
                    window,
                    capture,
                    { result -> continuation.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            }.onFailure { continuation.resume(false) }
        }
        if (copied) value = withContext(Dispatchers.Default) { capture.frosted().asImageBitmap() }
    }
    return image
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val FROST_WIDTH = 192
private const val FROST_HEIGHT = 108
private const val FROST_RADIUS = 7

/** This image, shrunk and blurred into frost. Blocking. */
internal fun Bitmap.frosted(): Bitmap =
    Bitmap.createScaledBitmap(this, FROST_WIDTH, FROST_HEIGHT, true).blurred(FROST_RADIUS)

/**
 * A blurred copy of this bitmap: three box-blur passes, which approximate a Gaussian. Blocking, and
 * meant for small images; everything larger gets scaled down first.
 */
fun Bitmap.blurred(radius: Int): Bitmap {
    val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    // Blurred premultiplied, so a transparent pixel's hidden color can't bleed into its neighbors.
    for (i in pixels.indices) pixels[i] = premultiply(pixels[i])
    repeat(3) { boxBlur(pixels, width, height, radius) }
    for (i in pixels.indices) pixels[i] = unpremultiply(pixels[i])
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun premultiply(c: Int): Int {
    val a = c ushr 24
    return (a shl 24) or
        (((c shr 16 and 0xFF) * a / 255) shl 16) or
        (((c shr 8 and 0xFF) * a / 255) shl 8) or
        ((c and 0xFF) * a / 255)
}

private fun unpremultiply(c: Int): Int {
    val a = c ushr 24
    if (a == 0) return 0
    return (a shl 24) or
        (((c shr 16 and 0xFF) * 255 / a).coerceAtMost(255) shl 16) or
        (((c shr 8 and 0xFF) * 255 / a).coerceAtMost(255) shl 8) or
        ((c and 0xFF) * 255 / a).coerceAtMost(255)
}

/** One separable box-blur pass, in place. */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val out = IntArray(pixels.size)
    fun pass(src: IntArray, dst: IntArray, length: Int, lines: Int, step: Int, lineStep: Int) {
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val c = src[line * lineStep + i.plus(k).coerceIn(0, length - 1) * step]
                    a += c ushr 24
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                val n = 2 * radius + 1
                dst[line * lineStep + i * step] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
    pass(pixels, out, width, height, step = 1, lineStep = width)
    pass(out, pixels, height, width, step = width, lineStep = 1)
}
