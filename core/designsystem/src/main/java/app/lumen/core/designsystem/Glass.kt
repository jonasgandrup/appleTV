package app.lumen.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.min

/*
 * Liquid glass, tuned for the Chromecast's GPU: a live blur or refraction shader is too heavy while
 * tiles animate, so nothing here blurs per frame. Glass gets its frost from images blurred once on
 * the CPU. Its liquid look comes from what's drawn over that, each a simple shape: at the edge, where
 * real glass bends light, bands of the frost pulled from further out, as a lens would; a double rim
 * giving the sheet thickness; a soft highlight; and a bounce of light along the bottom edge.
 */

/**
 * A blurred copy of what's behind glass, stretched over the whole window. Glass draws its own
 * slice of it, so sharp content behind the glass turns to frost. Null when the background is
 * already smooth, like a gradient, and the glass's tint is enough.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<ImageBitmap?> { null }

/**
 * Frost of a backdrop that changes while the glass stays put, like the home screen's stage. Read
 * only while drawing, so a new image repaints glass without recomposing anything.
 */
@Stable
class LiveFrost {
    var image by mutableStateOf<ImageBitmap?>(null)
    var previous by mutableStateOf<ImageBitmap?>(null)
    /** How far [image] has faded in over [previous], 0..1. */
    var fade by mutableFloatStateOf(1f)
}

/** Takes over from [LocalGlassBackdrop] whenever it has an image. */
val LocalLiveFrost = staticCompositionLocalOf<LiveFrost?> { null }

/**
 * A floating sheet of glass behind this element's content. Draws outside its bounds for its shadow.
 */
fun Modifier.liquidGlass(corner: Dp, tint: Color = LumenColors.GlassTint): Modifier = composed {
    val fixedBackdrop = LocalGlassBackdrop.current
    val liveFrost = LocalLiveFrost.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var window by remember { mutableStateOf(IntSize.Zero) }

    this
        .onGloballyPositioned {
            origin = it.positionInRoot()
            window = it.findRootCoordinates().size
        }
        .drawWithCache {
            val radius = min(corner.toPx(), size.minDimension / 2)
            val highlight = Brush.radialGradient(
                listOf(Color.White.copy(alpha = HIGHLIGHT_ALPHA), Color.Transparent),
                center = Offset(size.width * 0.15f, 0f),
                radius = min(size.width * 0.5f, size.height * 3f),
            )
            val outer = EDGE_OUTER.toPx()
            val inner = EDGE_INNER.toPx()
            val thickness = 1.5.dp.toPx()
            val hasEdge = size.minDimension > outer * 3
            // The tint, plus a light bounce along the bottom edge: one pass over the glass, not two.
            val fill = Brush.verticalGradient(
                0.6f to tint,
                1f to Color.White.copy(alpha = 0.07f).compositeOver(tint),
            )
            val corners = CornerRadius(radius)
            // A shader inside a rounded rect rather than a bitmap inside a rounded clip, which would
            // have the GPU build a mask for the glass on every frame.
            val brushes = HashMap<Pair<ImageBitmap, Float>, ShaderBrush>(6)
            var brushOrigin = origin
            /** [image] sliced for this glass. Below a [lens] of 1, it shows what lies further out, drawn inward. */
            fun brushFor(image: ImageBitmap, lens: Float = 1f): ShaderBrush {
                // The glass moved, as the dock does when the home screen scrolls, so the frost has to slide under it.
                if (brushOrigin != origin) {
                    brushes.clear()
                    brushOrigin = origin
                }
                return brushes.getOrPut(image to lens) {
                    val shader = BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    shader.setLocalMatrix(
                        Matrix().apply {
                            setScale(window.width.toFloat() / image.width, window.height.toFloat() / image.height)
                            postTranslate(-origin.x, -origin.y)
                            postScale(lens, lens, size.width / 2, size.height / 2)
                        },
                    )
                    ShaderBrush(shader)
                }
            }
            onDrawWithContent {
                drawGlassShadow(radius)
                val live = liveFrost?.image
                val backdrop = live ?: fixedBackdrop
                if (backdrop != null && window != IntSize.Zero) {
                    val fade = if (live != null) liveFrost.fade else 1f
                    // Solid underneath, so the glass doesn't turn see-through halfway.
                    if (fade < 1f) liveFrost?.previous?.let { drawRoundRect(brushFor(it), cornerRadius = corners) }
                    drawRoundRect(brushFor(backdrop), cornerRadius = corners, alpha = fade)
                    // The window's scrims don't reach the frost, so it would glow without this.
                    drawRoundRect(Color.Black.copy(alpha = GLASS_DIM), cornerRadius = corners)
                    if (hasEdge) {
                        // Where glass curves away, it bends in light from around it. Narrower bands bend it further.
                        drawEdgeBand(brushFor(backdrop, LENS_OUTER), outer, radius, EDGE_OUTER_ALPHA * fade)
                        drawEdgeBand(brushFor(backdrop, LENS_INNER), inner, radius, EDGE_INNER_ALPHA * fade)
                    }
                }
                drawRoundRect(fill, cornerRadius = corners)
                drawRoundRect(highlight, cornerRadius = corners)
                if (hasEdge) {
                    // A second, fainter rim inside the first gives the sheet some thickness.
                    drawRoundRect(
                        Color.White.copy(alpha = 0.07f),
                        topLeft = Offset(thickness, thickness),
                        size = Size(size.width - thickness * 2, size.height - thickness * 2),
                        cornerRadius = CornerRadius(radius - thickness),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                drawGlassRim(corner)
                drawContent()
            }
        }
}

private val EDGE_OUTER = 9.dp
private val EDGE_INNER = 4.dp
private const val LENS_OUTER = 0.9f
private const val LENS_INNER = 0.78f
private const val EDGE_OUTER_ALPHA = 0.32f
private const val EDGE_INNER_ALPHA = 0.5f
private const val GLASS_DIM = 0.2f
private const val HIGHLIGHT_ALPHA = 0.14f

/** A band of width [width] along the inside of the glass's edge. */
private fun DrawScope.drawEdgeBand(brush: Brush, width: Float, radius: Float, alpha: Float) {
    drawRoundRect(
        brush,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius((radius - width / 2).coerceAtLeast(0f)),
        style = Stroke(width),
        alpha = alpha,
    )
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
private const val CLEAR_FROST_RADIUS = 7

/** This image, shrunk and blurred into frost. Blocking. */
fun Bitmap.frosted(): Bitmap =
    Bitmap.createScaledBitmap(this, FROST_WIDTH, FROST_HEIGHT, true).blurred(FROST_RADIUS)

/**
 * A lighter frost, for tvOS's clear glass over rich media: what's behind it stays recognizable while
 * text on it wouldn't, so it's for controls that carry pictures, like the top row. Blocking.
 */
fun Bitmap.clearFrosted(): Bitmap =
    Bitmap.createScaledBitmap(this, FROST_WIDTH * 2, FROST_HEIGHT * 2, true).blurred(CLEAR_FROST_RADIUS)

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
