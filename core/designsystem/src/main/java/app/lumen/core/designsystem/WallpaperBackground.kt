package app.lumen.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.Wallpaper
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draws a [Wallpaper] full-bleed, crossfading when it changes. */
@Composable
fun WallpaperBackground(wallpaper: Wallpaper, modifier: Modifier = Modifier) {
    Crossfade(targetState = wallpaper, animationSpec = tween(600), modifier = modifier, label = "wallpaper") { target ->
        when (target) {
            is Wallpaper.Gradient -> GradientFill(target.preset, Modifier.fillMaxSize())
            is Wallpaper.Image -> Box(Modifier.fillMaxSize()) {
                GradientFill(GradientPreset.Midnight, Modifier.fillMaxSize())
                val image = rememberWallpaperImage(target.path)
                Crossfade(targetState = image, animationSpec = tween(500), label = "wallpaperImage") { bitmap ->
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

@Composable
fun GradientFill(preset: GradientPreset, modifier: Modifier = Modifier) {
    val image = remember(preset) { renderGradient(preset).asImageBitmap() }
    Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
}

/**
 * Bakes a gradient once, so drawing it is one stretched bitmap instead of a stack of full-screen
 * gradient shaders on every frame. The light sources give glass in front of it something to catch.
 */
fun renderGradient(preset: GradientPreset): Bitmap {
    val width = GRADIENT_WIDTH.toFloat()
    val height = GRADIENT_HEIGHT.toFloat()
    val bitmap = Bitmap.createBitmap(GRADIENT_WIDTH, GRADIENT_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isDither = true }
    paint.shader = LinearGradient(0f, 0f, width, height, preset.stops.map { it.toInt() }.toIntArray(), null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width, height, paint)
    // A soft light source in the upper right, like a stage light behind the shelf, and a fainter
    // one low on the left.
    paint.shader = radialLight(preset.glow, 0xC0, width * 0.85f, height * 0.05f, width * 0.7f)
    canvas.drawRect(0f, 0f, width, height, paint)
    paint.shader = radialLight(preset.accent, 0xA0, width * 0.1f, height * 0.95f, width * 0.6f)
    canvas.drawRect(0f, 0f, width, height, paint)
    return bitmap
}

private fun radialLight(color: Long, alpha: Int, x: Float, y: Float, radius: Float): RadialGradient {
    val rgb = color.toInt() and 0xFFFFFF
    return RadialGradient(x, y, radius, (alpha shl 24) or rgb, rgb, Shader.TileMode.CLAMP)
}

private const val GRADIENT_WIDTH = 480
private const val GRADIENT_HEIGHT = 270

/** A frosted copy of [wallpaper] to provide as [LocalGlassBackdrop]; null for gradients, which are smooth already. */
@Composable
fun rememberFrostedWallpaper(wallpaper: Wallpaper): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, wallpaper) {
        value = (wallpaper as? Wallpaper.Image)?.let {
            withContext(Dispatchers.IO) { WallpaperLibrary.decode(it.path, FROST_DECODE_WIDTH)?.frosted()?.asImageBitmap() }
        }
    }
    return image
}

private const val FROST_DECODE_WIDTH = 480

/** Decodes a wallpaper file off the main thread, sized for the screen (or [maxWidth]). */
@Composable
fun rememberWallpaperImage(path: String, maxWidth: Int? = null): ImageBitmap? {
    val context = LocalContext.current
    val width = maxWidth ?: context.resources.displayMetrics.widthPixels
    val image by produceState<ImageBitmap?>(null, path, width) {
        value = withContext(Dispatchers.IO) { WallpaperLibrary.decode(path, width)?.asImageBitmap() }
    }
    return image
}
