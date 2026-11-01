package app.lumen.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Android TV renders the UI at 1920x1080 (xhdpi), i.e. a 960x540 dp canvas, and the
 * Chromecast upscales it to 4K. Everything here is sized for that canvas.
 */

object LumenColors {
    val Background = Color(0xFF000000)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xA3FFFFFF)
    val TextTertiary = Color(0x66FFFFFF)
    val Surface = Color(0x1FFFFFFF)
    val SurfaceRaised = Color(0xFF1C1C1E)
    val Focused = Color(0xFFFFFFFF)
    val OnFocused = Color(0xFF0C0C0E)
}

object LumenDimens {
    /** Overscan-safe margins from the Android TV design guidelines. */
    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp

    val TileWidth = 152.dp
    val TileHeight = 86.dp
    val TileSpacing = 24.dp
    val TileCorner = 12.dp
    const val TilesPerRow = 5
}

object LumenType {
    /** Inter's variable font, cut at its text optical size. */
    private val Family = inter(opticalSize = 14f)

    /** Inter's display optical size: tighter spacing and finer details for large headlines. */
    private val DisplayFamily = inter(opticalSize = 32f)

    @OptIn(ExperimentalTextApi::class)
    private fun inter(opticalSize: Float) = FontFamily(
        listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(
                R.font.inter,
                weight,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight.weight),
                    FontVariation.Setting("opsz", opticalSize),
                ),
            )
        },
    )

    val Display = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.6).sp,
    )
    val Title = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp,
    )
    val Headline = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp,
    )
    val Body = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp,
    )
    val Section = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    )
    val Caption = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    )
    val Overline = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp,
    )
}
