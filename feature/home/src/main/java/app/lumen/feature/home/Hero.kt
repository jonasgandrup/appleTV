package app.lumen.feature.home

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.lumen.core.apps.AppInfo
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.WallpaperBackground
import app.lumen.core.settings.Wallpaper

/** The cinematic area above the Top Shelf, describing whichever app has focus. */
@Composable
internal fun Hero(app: AppInfo?, visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 450 else 200), label = "heroAlpha")
    AnimatedContent(
        targetState = app,
        contentKey = { it?.packageName },
        transitionSpec = {
            (fadeIn(tween(420, delayMillis = 60)) + slideInHorizontally(tween(420)) { it / 30 }) togetherWith
                fadeOut(tween(180))
        },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        label = "hero",
    ) { target ->
        if (target != null) HeroContent(target)
    }
}

@Composable
private fun HeroContent(app: AppInfo) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = LumenDimens.SafeHorizontal)
            .padding(bottom = 6.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.52f)) {
            LumenText(app.category.displayName.uppercase(), LumenType.Overline, color = LumenColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            LumenText(app.label, LumenType.Display, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            LumenText("Press select to open  ·  Hold for options", LumenType.Body, color = LumenColors.TextTertiary)
        }
        HeroArt(app, Modifier.align(Alignment.BottomEnd).width(320.dp))
    }
}

@Composable
private fun HeroArt(app: AppInfo, modifier: Modifier) {
    val banner = remember(app.banner) { app.banner?.asImageBitmap() }
    val icon = remember(app.icon) { app.icon.asImageBitmap() }
    val accent = Color(app.accentColor)
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .shadow(36.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.16f), accent))),
        contentAlignment = Alignment.Center,
    ) {
        if (banner != null) {
            Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        } else {
            Image(icon, app.label, Modifier.size(96.dp), filterQuality = FilterQuality.High)
        }
    }
}

/**
 * Wallpaper, plus (optionally) an ambient wash of the focused app's colors, plus the scrims
 * that keep text legible. [dim] darkens everything while browsing below the Top Shelf.
 */
@Composable
internal fun Backdrop(wallpaper: Wallpaper, art: Bitmap?, dim: Float) {
    val dimAlpha by animateFloatAsState(dim, tween(450), label = "dim")
    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        WallpaperBackground(wallpaper, Modifier.fillMaxSize())
        AmbientArt(art)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.6f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimAlpha }.background(Color.Black))
    }
}

@Composable
private fun AmbientArt(art: Bitmap?) {
    // Upscaling a tiny bitmap with bilinear filtering is a nearly free blur that works on every
    // API level; on Android 12+ a real blur pass smooths out the remaining banding.
    val ambient = remember(art) { art?.let(::shrink)?.asImageBitmap() }
    Crossfade(targetState = ambient, animationSpec = tween(700), label = "ambient") { image ->
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp, BlurredEdgeTreatment.Rectangle) else Modifier)
                    .graphicsLayer { alpha = 0.8f },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

private fun shrink(source: Bitmap): Bitmap {
    val mid = Bitmap.createScaledBitmap(source, 96, 54, true)
    return Bitmap.createScaledBitmap(mid, 24, 14, true)
}
