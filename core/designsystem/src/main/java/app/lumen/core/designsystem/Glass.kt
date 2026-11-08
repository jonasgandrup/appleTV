package app.lumen.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.findRootCoordinates
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
 * Liquid glass, tuned for the Chromecast's GPU: a live blur is too heavy while tiles animate, so
 * nothing here blurs per frame. Glass gets its frost from images blurred once on the CPU. Its liquid
 * look comes from what's drawn over that: at the edge, where real glass bends light, a shader bends
 * the frost in one smooth curve and catches a little light; a faint rim; a soft highlight; and a
 * bounce of light along the bottom edge. Without shaders, on older systems, the frost stays flat.
 */

/**
 * A blurred copy of what's behind glass, stretched over the whole window. Glass draws its own
 * slice of it, so sharp content behind the glass turns to frost. Null when the background is
 * already smooth, like a gradient, and the glass's tint is enough.
 *
 * Not a static local: glass is a modifier node that has to hear when this changes, as when a
 * screen's frosted capture arrives a moment after Control Center opens, and only a plain
 * composition local's changes reach a node watching it.
 */
val LocalGlassBackdrop = compositionLocalOf<ImageBitmap?> { null }

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
    /** A much softer, darker copy for filling the screen behind tiles. See [backgroundFrosted]. */
    var background by mutableStateOf<ImageBitmap?>(null)
}

/** Takes over from [LocalGlassBackdrop] whenever it has an image. */
val LocalLiveFrost = compositionLocalOf<LiveFrost?> { null }

/**
 * A floating sheet of glass behind this element's content. Draws outside its bounds for its shadow.
 * With [clear], it's tvOS's clear glass for controls that carry pictures: what's behind stays bright
 * and the edge nearly disappears, with no shadow. Text on it wouldn't stay readable.
 *
 * The glass is drawn once into a layer of its own and reused from there, being drawn again only when
 * what it shows changes: its size or place, the frost behind it, or a fade between two frosts. The
 * Chromecast repaints the whole screen on every frame of any animation, and drawing all of the glass
 * afresh each time held a move along the top row to about 40 frames a second. The content stays out
 * of the layer, so text on glass that grows with focus is drawn sharp at its new size.
 */
fun Modifier.liquidGlass(corner: Dp, tint: Color = LumenColors.GlassTint, clear: Boolean = false): Modifier =
    this.then(LiquidGlassElement(corner, tint, clear))

private data class LiquidGlassElement(val corner: Dp, val tint: Color, val clear: Boolean) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create() = LiquidGlassNode(corner, tint, clear)

    override fun update(node: LiquidGlassNode) = node.update(corner, tint, clear)
}

/** A modifier node rather than a composed modifier, so glass costs no composition of its own. */
private class LiquidGlassNode(
    private var corner: Dp,
    private var tint: Color,
    private var clear: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {
    private var fixedBackdrop: ImageBitmap? = null
    private var liveFrost: LiveFrost? = null
    private var origin = Offset.Zero
    private var window = IntSize.Zero
    private var layer: GraphicsLayer? = null
    /** What the layer was last drawn with, so it's drawn again only when one of these changes. */
    private var drawnFor: List<Any?>? = null
    private var shape: GlassShape? = null

    fun update(corner: Dp, tint: Color, clear: Boolean) {
        if (corner == this.corner && tint == this.tint && clear == this.clear) return
        this.corner = corner
        this.tint = tint
        this.clear = clear
        shape = null
        drawnFor = null
        invalidateDraw()
    }

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        readLocals()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        drawnFor = null
    }

    // The frost behind can change, as when a screen's capture arrives, so these are watched rather than read once.
    override fun onObservedReadsChanged() {
        readLocals()
        invalidateDraw()
    }

    private fun readLocals() = observeReads {
        fixedBackdrop = currentValueOf(LocalGlassBackdrop)
        liveFrost = currentValueOf(LocalLiveFrost)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val newOrigin = coordinates.positionInRoot()
        val newWindow = coordinates.findRootCoordinates().size
        if (newOrigin == origin && newWindow == window) return
        // The glass moved, as the dock does when the home screen scrolls, so the frost has to slide under it.
        origin = newOrigin
        window = newWindow
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val layer = layer ?: return drawContent()
        val shape = shape?.takeIf { it.size == size && it.density == density } ?: GlassShape(this, corner, tint, clear).also {
            shape = it
            drawnFor = null
        }
        val frost = liveFrost
        val live = frost?.image
        val backdrop = (live ?: fixedBackdrop)?.takeIf { window != IntSize.Zero }
        val fade = if (live != null && backdrop != null) frost.fade else 1f
        val previous = frost?.previous?.takeIf { fade < 1f && backdrop != null }
        val key = listOf(backdrop, previous, fade, origin, window)
        if (key != drawnFor) {
            drawnFor = key
            val margin = shape.margin
            layer.topLeft = IntOffset(-margin.roundToInt(), -margin.roundToInt())
            // Its own Density: handed this scope, the recording would ask it for its density, which asks the recording.
            layer.record(Density(density, fontScale), layoutDirection, IntSize((size.width + margin * 2).roundToInt(), (size.height + margin * 2).roundToInt())) {
                translate(margin, margin) {
                    // The glass's own size, not the layer's, for everything measured from its edges.
                    withSize(shape.size) { drawGlass(shape, backdrop, previous, fade) }
                }
            }
        }
        drawLayer(layer)
        drawContent()
    }

    /** [image] sliced for this glass, where there are no shaders to bend it. */
    private fun flatBrush(image: ImageBitmap): ShaderBrush {
        val shader = BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(window.width.toFloat() / image.width, window.height.toFloat() / image.height)
                postTranslate(-origin.x, -origin.y)
            },
        )
        return ShaderBrush(shader)
    }

    private fun DrawScope.drawGlass(shape: GlassShape, backdrop: ImageBitmap?, previous: ImageBitmap?, fade: Float) {
        val corners = shape.corners
        if (!clear) drawGlassShadow(shape.radius)
        if (backdrop != null) {
            // Solid underneath, so the glass doesn't turn see-through halfway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                previous?.let { drawRoundRect(GlassLens.bent(it, window, origin, size, shape.radius, shape.bend), cornerRadius = corners) }
                drawRoundRect(GlassLens.bent(backdrop, window, origin, size, shape.radius, shape.bend), cornerRadius = corners, alpha = fade)
            } else {
                previous?.let { drawRoundRect(flatBrush(it), cornerRadius = corners) }
                drawRoundRect(flatBrush(backdrop), cornerRadius = corners, alpha = fade)
            }
            // The window's scrims don't reach the frost, so it would glow without this.
            drawRoundRect(Color.Black.copy(alpha = if (clear) CLEAR_GLASS_DIM else GLASS_DIM), cornerRadius = corners)
        }
        drawRoundRect(shape.fill, cornerRadius = corners)
        drawRoundRect(shape.highlight, cornerRadius = corners)
        // With no frost to bend, as over a moving video, its edge still catches the light.
        if (backdrop == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawRoundRect(GlassLens.edgeLight(size, shape.radius, shape.bend), cornerRadius = corners)
        }
        drawGlassRim(corner, strength = if (clear) CLEAR_RIM_STRENGTH else GLASS_RIM_STRENGTH)
    }
}

/** What a sheet of glass of one size looks like, worked out once rather than on every draw. */
private class GlassShape(scope: DrawScope, corner: Dp, tint: Color, clear: Boolean) {
    val size = scope.size
    val density = scope.density
    val radius = min(with(scope) { corner.toPx() }, size.minDimension / 2)
    val corners = CornerRadius(radius)
    val highlight = Brush.radialGradient(
        listOf(Color.White.copy(alpha = HIGHLIGHT_ALPHA), Color.Transparent),
        center = Offset(size.width * 0.15f, 0f),
        radius = min(size.width * 0.5f, size.height * 3f),
    )
    // Bends at most as wide as the corners, so the two straight edges' bends can't meet in a visible seam.
    val bend = minOf(with(scope) { LENS_BEND.toPx() }, radius, size.minDimension * LENS_MAX_SHARE).coerceAtLeast(1f)
    // The tint, plus a light bounce along the bottom edge: one pass over the glass, not two.
    val fill = Brush.verticalGradient(
        0.6f to tint,
        1f to Color.White.copy(alpha = 0.07f).compositeOver(tint),
    )
    /** Room in the layer around the glass for its shadow. */
    val margin = if (clear) 0f else with(scope) { SHADOW_STEP.toPx() } * SHADOW_RINGS
}

/** Runs [block] with [size] standing in for this scope's size, for drawing that measures from its own edges. */
private inline fun DrawScope.withSize(size: Size, crossinline block: DrawScope.() -> Unit) {
    val scope = this
    drawContext.size.let { previous ->
        drawContext.size = size
        try {
            scope.block()
        } finally {
            drawContext.size = previous
        }
    }
}

/**
 * Frost bent the way a thick sheet of glass bends light: near the edge each pixel looks further
 * outward, in one smooth curve, and catches a little light. Past the bend the frost is drawn as it is.
 *
 * One shader serves all the glass on screen, since compiling one for each would make Control Center
 * stumble as it opens. That's safe to reuse between draws: a recorded draw keeps its own copy of the values.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object GlassLens {
    private val bentFrost = RuntimeShader(BENT_FROST_SHADER)
    private val bentFrostBrush = ShaderBrush(bentFrost)
    private val light = RuntimeShader(EDGE_LIGHT_SHADER)
    private val lightBrush = ShaderBrush(light)
    private val frosts = HashMap<Pair<ImageBitmap, IntSize>, BitmapShader>()

    fun bent(image: ImageBitmap, window: IntSize, origin: Offset, size: Size, radius: Float, bend: Float): ShaderBrush {
        // Few are in use at once. Cleared rather than kept, so replaced artwork doesn't stay in memory.
        if (frosts.size >= MAX_FROSTS) frosts.keys.retainAll { it.first === image }
        val frost = frosts.getOrPut(image to window) {
            BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                // Inside a shader, the paint's own filtering doesn't reach it.
                filterMode = BitmapShader.FILTER_MODE_LINEAR
                setLocalMatrix(Matrix().apply { setScale(window.width.toFloat() / image.width, window.height.toFloat() / image.height) })
            }
        }
        bentFrost.setInputShader("frost", frost)
        bentFrost.setFloatUniform("origin", origin.x, origin.y)
        bentFrost.setShape(size, radius, bend)
        return bentFrostBrush
    }

    fun edgeLight(size: Size, radius: Float, bend: Float): ShaderBrush {
        light.setShape(size, radius, bend)
        return lightBrush
    }

    private fun RuntimeShader.setShape(size: Size, radius: Float, bend: Float) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("radius", radius)
        setFloatUniform("bend", bend)
    }
}

private const val MAX_FROSTS = 4

private const val GLASS_EDGE_SHADER = """
uniform float2 size;
uniform float radius;
uniform float bend;

// How near the edge this is, from 0 inside the bend to 1 at the edge, and which way is out.
float edgeAt(float2 coord, out float2 outward) {
    float2 p = coord - size * 0.5;
    float2 q = abs(p) - size * 0.5 + radius;
    float2 corner = max(q, 0.0);
    outward = corner.x + corner.y > 0.0 ? normalize(corner) * sign(p) : float2(0.0);
    float depth = radius - length(corner) - min(max(q.x, q.y), 0.0);
    return 1.0 - clamp(depth / bend, 0.0, 1.0);
}

// Brightest where the light lands at the top left.
float lightAt(float edge, float2 outward) {
    return edge * edge * (0.05 + 0.09 * max(dot(outward, float2(-0.5, -0.85)), 0.0));
}
"""

private const val BENT_FROST_SHADER = GLASS_EDGE_SHADER + """
uniform shader frost;
uniform float2 origin;

half4 main(float2 coord) {
    float2 outward;
    float edge = edgeAt(coord, outward);
    float3 color = frost.eval(coord + origin + outward * edge * edge * edge * bend * 1.45).rgb + lightAt(edge, outward);
    // Dithered, so a slow fade in a dark picture can't show as steps.
    color += (fract(52.9829189 * fract(dot(coord, float2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    return half4(half3(color), 1.0);
}
"""

private const val EDGE_LIGHT_SHADER = GLASS_EDGE_SHADER + """
half4 main(float2 coord) {
    float2 outward;
    float edge = edgeAt(coord, outward);
    return half4(half(lightAt(edge, outward)));
}
"""

private val LENS_BEND = 18.dp
private const val LENS_MAX_SHARE = 0.3f
private const val GLASS_DIM = 0.2f
private const val CLEAR_GLASS_DIM = 0.08f
private const val GLASS_RIM_STRENGTH = 0.6f
private const val CLEAR_RIM_STRENGTH = 0.4f
private const val HIGHLIGHT_ALPHA = 0.14f

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
    val step = SHADOW_STEP.toPx()
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
private val SHADOW_STEP = 3.dp
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
fun Bitmap.frosted(): Bitmap =
    Bitmap.createScaledBitmap(this, FROST_WIDTH, FROST_HEIGHT, true).blurred(FROST_RADIUS)

/**
 * Frost for a whole screen behind tiles, as tvOS's home screen has below the top row: blurred until
 * no shape can be made out, richer in color and darker, so tiles stand out on it. All of that is
 * baked in rather than drawn over it, sparing the GPU a full-screen pass. Blocking.
 */
fun Bitmap.backgroundFrosted(): Bitmap {
    val soft = Bitmap.createScaledBitmap(this, BACKGROUND_FROST_WIDTH, BACKGROUND_FROST_HEIGHT, true).blurred(BACKGROUND_FROST_RADIUS)
    val out = Bitmap.createBitmap(BACKGROUND_FROST_WIDTH, BACKGROUND_FROST_HEIGHT, Bitmap.Config.ARGB_8888)
    // Bright art is held down further, so white art doesn't leave a grey that white tiles get lost on.
    val pixels = IntArray(BACKGROUND_FROST_WIDTH * BACKGROUND_FROST_HEIGHT).also { soft.getPixels(it, 0, BACKGROUND_FROST_WIDTH, 0, 0, BACKGROUND_FROST_WIDTH, BACKGROUND_FROST_HEIGHT) }
    val luminance = pixels.sumOf { (0.2126 * (it shr 16 and 0xFF) + 0.7152 * (it shr 8 and 0xFF) + 0.0722 * (it and 0xFF)) / 255.0 } / pixels.size
    val brightness = minOf(BACKGROUND_FROST_BRIGHTNESS, BACKGROUND_FROST_MAX_LUMINANCE / luminance.toFloat().coerceAtLeast(0.01f))
    val matrix = ColorMatrix().apply {
        setSaturation(BACKGROUND_FROST_SATURATION)
        postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
    }
    Canvas(out).drawBitmap(soft, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    return out
}

private const val BACKGROUND_FROST_WIDTH = 96
private const val BACKGROUND_FROST_HEIGHT = 54
private const val BACKGROUND_FROST_RADIUS = 6
private const val BACKGROUND_FROST_SATURATION = 1.5f
private const val BACKGROUND_FROST_BRIGHTNESS = 0.55f
private const val BACKGROUND_FROST_MAX_LUMINANCE = 0.2f

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
