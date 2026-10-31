package app.lumen.core.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns whatever artwork an app ships into a uniform 16:9 tile, so every tile shares one frame
 * no matter how good the app's banner is.
 *
 * - A banner fills the tile. One with a different aspect ratio is fitted, not stretched, and
 *   the leftover space is filled with the banner's own edge color.
 * - Apps without a banner, or with a tiny one that would look blurry, get a card built from
 *   their icon: an adaptive icon's background layer fills the card with the logo centered on
 *   it, like a tvOS tile; other icons sit on a gradient of the app's accent color.
 */
internal object TileArt {

    fun render(banner: Drawable?, icon: Drawable, accent: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (banner != null && isSharp(banner)) {
            drawBanner(canvas, banner, accent)
        } else {
            drawIconCard(canvas, icon, accent)
        }
        return bitmap
    }

    /** Raster banners narrower than this are upscaled so far that they look soft on a TV. */
    private const val MIN_BANNER_PX = 240
    private const val TARGET_ASPECT = 16f / 9f
    /** Banners within 5% of 16:9 are drawn edge to edge; the slight distortion is invisible. */
    private const val ASPECT_TOLERANCE = 0.05f
    /** An adaptive icon's logo sits in its middle 66 of 108 units; this sizes it to ~60% of the tile height. */
    private const val ADAPTIVE_FOREGROUND_SCALE = 0.95f
    private const val LEGACY_ICON_SCALE = 0.5f
    private const val EDGE_SAMPLE_WIDTH = 64

    private fun isSharp(banner: Drawable): Boolean {
        val pixels = (banner as? BitmapDrawable)?.bitmap?.width ?: banner.intrinsicWidth
        return pixels <= 0 || pixels >= MIN_BANNER_PX
    }

    private fun drawBanner(canvas: Canvas, banner: Drawable, accent: Int) {
        val w = canvas.width
        val h = canvas.height
        val iw = banner.intrinsicWidth
        val ih = banner.intrinsicHeight
        val aspect = if (iw > 0 && ih > 0) iw.toFloat() / ih else TARGET_ASPECT

        if (abs(aspect / TARGET_ASPECT - 1f) <= ASPECT_TOLERANCE) {
            // Accent shows through any transparent parts of the banner.
            fillGradient(canvas, accent)
            banner.setBounds(0, 0, w, h)
        } else {
            val edge = edgeColor(banner, aspect)
            if (edge != null) canvas.drawColor(edge) else fillGradient(canvas, accent)
            val (bw, bh) = if (aspect > TARGET_ASPECT) w to (w / aspect).roundToInt() else (h * aspect).roundToInt() to h
            banner.setBounds((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }
        banner.draw(canvas)
    }

    private fun drawIconCard(canvas: Canvas, icon: Drawable, accent: Int) {
        val w = canvas.width
        val h = canvas.height
        if (icon is AdaptiveIconDrawable) {
            val background = icon.background
            if (background != null) {
                // Square layer, cropped top and bottom to the tile.
                background.setBounds(0, (h - w) / 2, w, (h + w) / 2)
                background.draw(canvas)
            } else {
                fillGradient(canvas, accent)
            }
            icon.foreground?.let { drawCentered(canvas, it, (h * ADAPTIVE_FOREGROUND_SCALE).roundToInt()) }
        } else {
            fillGradient(canvas, accent)
            drawCentered(canvas, icon, (h * LEGACY_ICON_SCALE).roundToInt())
        }
    }

    private fun drawCentered(canvas: Canvas, drawable: Drawable, size: Int) {
        val left = (canvas.width - size) / 2
        val top = (canvas.height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    /** The same top-lit accent gradient the UI uses behind tiles. */
    private fun fillGradient(canvas: Canvas, accent: Int) {
        val top = ColorUtils.blendARGB(accent, Color.WHITE, 0.16f)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, canvas.height.toFloat(), top, accent, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
    }

    /** Average color of the banner's opaque border pixels, or null if its border is mostly transparent. */
    private fun edgeColor(banner: Drawable, aspect: Float): Int? {
        val sw = EDGE_SAMPLE_WIDTH
        val sh = (sw / aspect).roundToInt().coerceAtLeast(2)
        val small = banner.toBitmap(sw, sh)
        var r = 0L
        var g = 0L
        var b = 0L
        var opaque = 0
        var total = 0
        fun sample(x: Int, y: Int) {
            total++
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 200) return
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
            opaque++
        }
        for (x in 0 until sw) {
            sample(x, 0)
            sample(x, sh - 1)
        }
        for (y in 1 until sh - 1) {
            sample(0, y)
            sample(sw - 1, y)
        }
        if (opaque * 2 < total) return null
        return Color.rgb((r / opaque).toInt(), (g / opaque).toInt(), (b / opaque).toInt())
    }
}
