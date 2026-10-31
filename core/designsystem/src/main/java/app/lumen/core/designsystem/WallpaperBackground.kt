package app.lumen.core.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.Wallpaper
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

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
    Box(
        modifier
            .background(Brush.linearGradient(preset.stops.map { Color(it) }, start = Offset.Zero, end = Offset.Infinite))
            .drawBehind {
                // A soft light source in the upper right, like a stage light behind the shelf.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(preset.glow).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.05f),
                        radius = size.width * 0.7f,
                    ),
                )
            },
    )
}

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
