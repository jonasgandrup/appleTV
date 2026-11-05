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
 * Chromecast upscales it to 4K. Everything here is sized for that canvas. Apple's tvOS numbers
 * are in points on a 1920-wide canvas, so each one taken from its guidelines is halved.
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
    /** Glass carrying text, like panels and menus. */
    val GlassTint = Color(0x12FFFFFF)
    /** Glass floating over artwork, like the dock. Dims what's behind it, as tvOS's does. */
    val GlassTintDim = Color(0x2E000000)
    /** Clear glass over artwork, like the dock, which should barely dim what's behind it. */
    val GlassTintClear = Color(0x14000000)
    /** Glass with focus on it. */
    val GlassFocused = Color(0xF2FFFFFF)
    /** For glass with nothing frosted behind it, where text has to stay readable by itself. */
    val GlassTintOpaque = Color(0xEB26262A)
}

object LumenDimens {
    /** tvOS safe area: 80 pt from the sides, 60 pt from the top and bottom. */
    val SafeHorizontal = 40.dp
    val SafeVertical = 30.dp

    /** tvOS's six-column grid: 260 pt wide with 40 pt gaps, shaped like its 5:3 app icons. */
    val TileWidth = 130.dp
    val TileHeight = 78.dp
    val TileSpacing = 20.dp
    val TileCorner = 14.dp
    const val TilesPerRow = 6

    /** Four 16:9 tiles across the grid's width, with a peek of the next; tvOS's Continue Watching proportions. */
    val ProgramTileWidth = 205.dp
    val ProgramTileHeight = 115.dp

    /** tvOS's Top Shelf row of 16:9 content, 782 by 440 pt: two tiles across with a peek of the third. */
    val ShelfRowTileWidth = 391.dp
    val ShelfRowTileHeight = 220.dp

    /** The dock's glass around its tiles: tight enough that a focused tile nearly fills it, as on tvOS. */
    val DockInset = 13.dp

    val GlassCorner = 28.dp
    /** Larger than any capsule is tall; glass clamps it to a full pill. */
    val CapsuleCorner = 100.dp
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

    /** The featured title; larger than tvOS's largest text style, standing in for its logo artwork. */
    val Display = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 46.sp, lineHeight = 52.sp, letterSpacing = (-0.7).sp,
    )
    /** tvOS Title 2. */
    val Title = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Medium,
        fontSize = 28.5.sp, lineHeight = 33.sp, letterSpacing = (-0.3).sp,
    )
    /** tvOS Headline. */
    val Headline = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 23.sp,
    )
    /** tvOS Body. */
    val Body = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp, lineHeight = 18.sp,
    )
    /** tvOS Callout. */
    val Section = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp, lineHeight = 19.sp,
    )
    /** A row in a list, a little above tvOS's Body in size and regular in weight, as its lists are. */
    val Row = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp, lineHeight = 20.sp,
    )
    /** tvOS Caption 1. */
    val Caption = TextStyle(
        fontFamily = Family, fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp, lineHeight = 16.sp,
    )
}
