package app.lumen.feature.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The screen saver's Aerials. Apple's own are its videos and can't be shipped here, so these are
 * original landscapes painted from noise. A scene takes seconds to paint on the Chromecast, so each
 * is painted once, off the main thread, and kept in the cache directory.
 */

internal enum class AerialScene(val title: String, val subtitle: String) {
    Earth("Earth", "From Orbit"),
    Dunes("Dunes", "Desert"),
    Ridges("Ridges", "Mountains at Sunrise"),
    Clouds("Above the Clouds", "Sunset"),
}

private const val SCENE_WIDTH = 1280
private const val SCENE_HEIGHT = 720
private const val SCENE_QUALITY = 92
/** Different variations of each scene before they repeat. */
private const val SCENE_SEEDS = 4

/** The picture for slide [index] of the Aerials, painting it first if it's new. Null if that fails. */
internal suspend fun aerialImage(context: Context, index: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val scenes = AerialScene.entries
    val scene = scenes[index.mod(scenes.size)]
    val seed = index.floorDiv(scenes.size).mod(SCENE_SEEDS)
    val file = File(File(context.cacheDir, "aerials").apply { mkdirs() }, "${scene.name}-$seed.jpg")
    if (!file.exists()) {
        val bitmap = paint(scene, seed)
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, SCENE_QUALITY, it) }
        bitmap.recycle()
        temporary.renameTo(file)
    }
    BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}

internal fun aerialScene(index: Int): AerialScene = AerialScene.entries[index.mod(AerialScene.entries.size)]

private suspend fun paint(scene: AerialScene, seed: Int): Bitmap = coroutineScope {
    val noise = Noise(seed * 7919L + scene.ordinal)
    val pixels = IntArray(SCENE_WIDTH * SCENE_HEIGHT)
    val painter = when (scene) {
        AerialScene.Earth -> ::earth
        AerialScene.Dunes -> ::dunes
        AerialScene.Ridges -> ridges(noise)
        AerialScene.Clouds -> ::clouds
    }
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    (0 until workers).map { worker ->
        async(Dispatchers.Default) {
            val c = Rgb()
            var row = worker
            while (row < SCENE_HEIGHT) {
                val y = row.toFloat() / SCENE_HEIGHT
                for (column in 0 until SCENE_WIDTH) {
                    val ax = column.toFloat() / SCENE_HEIGHT
                    painter(noise, ax, y, c)
                    // A touch of grain, which keeps smooth skies from banding on a TV.
                    pixels[row * SCENE_WIDTH + column] = c.toColor(noise.random(column, row) - 0.5f)
                }
                row += workers
            }
        }
    }.awaitAll()
    Bitmap.createBitmap(pixels, SCENE_WIDTH, SCENE_HEIGHT, Bitmap.Config.ARGB_8888)
}

/** Value noise and its octaves, repeatable for a seed. */
private class Noise(seed: Long) {
    private val random = java.util.Random(seed)
    private val values = FloatArray(256) { random.nextFloat() }
    private val order = IntArray(512).also { table ->
        val shuffled = (0 until 256).shuffled(random)
        for (i in table.indices) table[i] = shuffled[i and 255]
    }

    fun at(x: Float, y: Float): Float {
        val fx = floor(x)
        val fy = floor(y)
        val xi = fx.toInt()
        val yi = fy.toInt()
        val tx = x - fx
        val ty = y - fy
        val u = tx * tx * (3 - 2 * tx)
        val v = ty * ty * (3 - 2 * ty)
        val a = value(xi, yi)
        val b = value(xi + 1, yi)
        val c = value(xi, yi + 1)
        val d = value(xi + 1, yi + 1)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    private fun value(x: Int, y: Int) = values[order[(order[x and 255] + y) and 255]]

    fun fbm(x: Float, y: Float, octaves: Int, gain: Float = 0.5f): Float {
        var px = x
        var py = y
        var total = 0f
        var amplitude = 0.5f
        var norm = 0f
        repeat(octaves) {
            total += amplitude * at(px, py)
            norm += amplitude
            px = px * 2.03f + 11.3f
            py = py * 2.03f + 7.1f
            amplitude *= gain
        }
        return total / norm
    }

    /** A repeatable random number per pixel, 0..1. */
    fun random(x: Int, y: Int): Float {
        val h = (x * 374761393 + y * 668265263).let { (it xor (it ushr 13)) * 1274126177 }
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }
}

/** A color being mixed for one pixel. One per thread, so painting allocates nothing per pixel. */
private class Rgb {
    var r = 0f
    var g = 0f
    var b = 0f

    fun set(r: Float, g: Float, b: Float) {
        this.r = r
        this.g = g
        this.b = b
    }

    fun mix(r: Float, g: Float, b: Float, t: Float) {
        this.r += (r - this.r) * t
        this.g += (g - this.g) * t
        this.b += (b - this.b) * t
    }

    fun add(r: Float, g: Float, b: Float, k: Float) {
        this.r += r * k
        this.g += g * k
        this.b += b * k
    }

    fun toColor(grain: Float): Int {
        fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + grain).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private fun square(x: Float) = x * x

/** Looking across a sea of cloud toward a low sun; rows crowd together toward the horizon. */
private fun clouds(noise: Noise, ax: Float, y: Float, c: Rgb) {
    val horizon = 0.30f
    val d = max(y - horizon, 0.002f)
    val s = d * 3f + 0.1f
    val px = (ax - 0.9f) / s * 0.9f
    val py = 1f / s
    val warp = noise.fbm(px * 0.5f, py * 0.5f, 4)
    val cx = px * 0.9f + warp * 1.8f
    val cy = py * 0.9f + warp * 1.8f
    val cloud = noise.fbm(cx, cy, 6)
    val light = ((cloud - noise.fbm(cx + 0.08f, cy + 0.1f, 6)) * 7f + 0.55f).coerceIn(0f, 1f)

    c.set(0.10f, 0.20f, 0.42f)
    c.mix(0.98f, 0.72f, 0.48f, square(smooth(0.32f, 0f, abs(y - horizon))))
    val sun = exp(-(square(ax - 1.15f) / 0.09f + square(y - horizon + 0.02f) / 0.012f))
    c.add(1f, 0.75f, 0.5f, sun * 0.9f)
    val skyR = c.r
    val skyG = c.g
    val skyB = c.b

    c.set(0.16f, 0.24f, 0.42f)
    c.mix(0.42f, 0.38f, 0.50f, light)
    val t = smooth(0.45f, 0.8f, cloud)
    c.mix(0.60f + 0.40f * light, 0.54f + 0.32f * light, 0.62f + 0.12f * light, t)
    val glow = exp(-square(ax - 1.15f) / 0.35f) * exp(-d * 3.5f)
    c.add(0.55f, 0.30f, 0.12f, glow * 0.8f)
    // Sky shows through wherever there's no cloud, and everywhere above the horizon.
    c.mix(skyR, skyG, skyB, 1f - smooth(0.38f, 0.62f, cloud) * smooth(horizon, horizon + 0.025f, y))
}

/** Sand seen from above: long ridges with a sharp crest, lit from one side. */
private fun dunes(noise: Noise, ax: Float, y: Float, c: Rgb) {
    fun height(x: Float, warp: Float): Float {
        val p = (x * 1.3f + y * 3.4f + warp) * 3.1f
        val f = p - floor(p)
        val ridge = if (f < 0.72f) f / 0.72f else (1 - f) / 0.28f
        return ridge.pow(1.3f) * 0.6f + noise.fbm(x * 3f, y * 5f + warp, 4) * 0.4f
    }
    val e = 2f / SCENE_HEIGHT
    val warp = noise.fbm(ax * 1.1f + 3f, y * 1.6f, 4) * 2.4f
    val warpBeside = noise.fbm((ax + e) * 1.1f + 3f, y * 1.6f, 4) * 2.4f
    val slope = (height(ax + e, warpBeside) - height(ax, warp)) / e * 0.018f
    var light = (0.58f - slope * 0.85f).coerceIn(0f, 1f)
    val ripple = kotlin.math.sin(ax * 40f + y * 90f + warp * 22f)
    light = (light + ripple * 0.018f * smooth(0.2f, 0.8f, light)).coerceIn(0f, 1f)
    c.set(0.30f, 0.12f, 0.10f)
    c.mix(0.80f, 0.42f, 0.20f, smooth(0.12f, 0.55f, light))
    c.mix(1f, 0.76f, 0.48f, smooth(0.5f, 0.95f, light))
}

private const val RIDGE_LAYERS = 6

/** Ranges fading into a sunrise haze. Each range's skyline is worked out once, so it's only looked up per pixel. */
private fun ridges(noise: Noise): (Noise, Float, Float, Rgb) -> Unit {
    val skylines = Array(RIDGE_LAYERS) { i ->
        val t = i / (RIDGE_LAYERS - 1f)
        val base = 0.44f + t * 0.46f
        val frequency = 1.5f + i * 0.75f
        FloatArray(SCENE_WIDTH) { column ->
            val ax = column.toFloat() / SCENE_HEIGHT
            base + (0.5f - noise.fbm(ax * frequency + i * 9.1f, i * 3.3f, 6, 0.52f)) * (0.40f - t * 0.12f)
        }
    }
    return { n, ax, y, c ->
        c.set(0.28f, 0.40f, 0.66f)
        c.mix(0.98f, 0.72f, 0.60f, smooth(0f, 0.62f, y))
        val sun = exp(-(square(ax - 0.6f) + square((y - 0.46f) * 1.2f)) / 0.022f)
        c.add(1f, 0.8f, 0.6f, sun * 0.75f)
        val column = min((ax * SCENE_HEIGHT).toInt(), SCENE_WIDTH - 1)
        val texture by lazy(LazyThreadSafetyMode.NONE) { n.fbm(ax * 22f, y * 22f, 3) }
        for (i in 0 until RIDGE_LAYERS) {
            val top = skylines[i][column]
            val mask = smooth(top - 0.002f, top + 0.002f, y)
            if (mask <= 0f) continue
            val t = i / (RIDGE_LAYERS - 1f)
            val nearness = t.pow(0.9f)
            val shade = 0.92f + texture * 0.16f
            val r = (0.66f + (0.05f - 0.66f) * nearness) * shade
            val g = (0.60f + (0.08f - 0.60f) * nearness) * shade
            val b = (0.66f + (0.13f - 0.66f) * nearness) * shade
            val haze = ((y - top) * 3f).coerceIn(0f, 1f) * (0.5f - t * 0.45f)
            c.mix(r + (0.95f - r) * haze, g + (0.72f - g) * haze, b + (0.62f - b) * haze, mask)
        }
    }
}

/** The Earth's edge from orbit at night, with sunrise along the rim and cities below. */
private fun earth(noise: Noise, ax: Float, y: Float, c: Rgb) {
    val cx = 1f
    val cy = 2.05f
    val radius = 1.72f
    val r = sqrt(square(ax - cx) + square(y - cy))
    val edge = r - radius
    val angle = atan2(ax - cx, cy - y)
    val dawn = exp(-square(angle - 0.38f) / 0.09f)
    if (edge >= 0f) {
        val column = (ax * SCENE_HEIGHT).toInt()
        val row = (y * SCENE_HEIGHT).toInt()
        val star = noise.random(column * 3 + 1, row * 5 + 2).let { if (it > 0.9985f && edge > 0.02f) noise.random(column, row) else 0f }
        c.set(star, star, star)
        val rim = exp(-edge / 0.03f)
        val air = exp(-edge / 0.11f) * 0.42f
        c.add(0.20f, 0.46f, 1f, rim * 0.9f + air)
        c.add(1f, 0.68f, 0.36f, dawn * exp(-edge / 0.035f) * 1.6f)
        return
    }
    val lat = (-edge / 0.9f).coerceIn(0f, 1f)
    val u = angle * 3.2f / (0.25f + lat * 0.75f)
    val v = 0.8f / (0.06f + lat * 1.2f)
    val land = smooth(0.46f, 0.60f, noise.fbm(u * 1.8f + 5f, v * 1.8f, 6))
    c.set(0.012f, 0.03f, 0.075f)
    c.mix(0.03f, 0.045f, 0.05f, land)
    if (land > 0f && noise.fbm(u * 26f, v * 26f, 3) > 0.70f) {
        val city = land * smooth(0.45f, 0.75f, noise.fbm(u * 4f + 9f, v * 4f, 4))
        c.add(1f, 0.72f, 0.38f, city * 0.9f * (0.5f + 0.5f * noise.random((ax * SCENE_HEIGHT).toInt(), (y * SCENE_HEIGHT).toInt())))
    }
    val cloud = smooth(0.52f, 0.72f, noise.fbm(u * 3.2f + 2f, v * 3.2f + noise.fbm(u, v, 3) * 2f, 6))
    c.add(0.9f, 0.85f, 0.85f, cloud * (0.03f + dawn * exp(-lat * 7f) * 0.55f))
    c.add(0.18f, 0.40f, 0.9f, exp(-lat * 26f) * 0.5f)
    c.add(1f, 0.55f, 0.28f, dawn * exp(-lat * 11f) * 0.7f)
    // The rim's glow carries over the edge, so the disc and the sky meet without a seam.
    c.add(0.20f, 0.46f, 1f, exp(edge / 0.004f) * 1.3f)
    c.add(1f, 0.68f, 0.36f, dawn * exp(edge / 0.004f) * 1.6f)
}
