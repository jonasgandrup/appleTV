package app.lumen.feature.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lumen.core.designsystem.GradientFill
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.R as DesignR
import app.lumen.core.designsystem.animatePressableScale
import app.lumen.core.designsystem.drawGlassRim
import app.lumen.core.designsystem.rememberWallpaperImage
import app.lumen.core.designsystem.tvClickable
import app.lumen.core.settings.Wallpaper
import java.io.File

private val SwatchWidth = 140.dp
private val SwatchHeight = 79.dp

@Composable
internal fun WallpaperSwatch(
    wallpaper: Wallpaper,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animatePressableScale(focused, pressed, 1.1f, "swatchScale")
    val elevation by animateDpAsState(if (focused) 20.dp else 0.dp, tween(250), label = "swatchElevation")
    val shape = RoundedCornerShape(LumenDimens.TileCorner)

    Column(Modifier.width(SwatchWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .size(SwatchWidth, SwatchHeight)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .tvClickable(onPressedChange = { pressed = it }, onClick = onClick)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(elevation, shape)
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    drawGlassRim(LumenDimens.TileCorner)
                },
        ) {
            when (wallpaper) {
                is Wallpaper.Gradient -> GradientFill(wallpaper.preset, Modifier.fillMaxSize())
                is Wallpaper.Image -> {
                    val thumb = rememberWallpaperImage(wallpaper.path, maxWidth = 320)
                    if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            // tvOS marks the current choice with a checkmark. A white outline here read as focus, and
            // competed with the swatch that really had it.
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(DesignR.drawable.ic_row_check),
                        "Selected",
                        Modifier.size(13.dp),
                        colorFilter = ColorFilter.tint(LumenColors.OnFocused),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LumenText(
            text = when (wallpaper) {
                is Wallpaper.Gradient -> wallpaper.preset.displayName
                is Wallpaper.Image -> File(wallpaper.path).nameWithoutExtension
            },
            style = LumenType.Caption,
            color = if (focused || selected) LumenColors.TextPrimary else LumenColors.TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
