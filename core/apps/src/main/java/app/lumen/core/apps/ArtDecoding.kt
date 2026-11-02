package app.lumen.core.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes [bytes] center-cropped to the shape of [width] by [height], keeping the top of tall art,
 * where faces and titles usually are. Null when it's narrower than [minWidth] or not landscape.
 *
 * With [upscale], the result is exactly [width] by [height]. Without it, art smaller than that keeps
 * its own size, as stretching it here would only cost memory; the GPU stretches it as cheaply.
 * Decodes subsampled when the source is far larger, so a 4K poster never sits in memory whole.
 */
internal fun decodeArt(bytes: ByteArray, width: Int, height: Int, upscale: Boolean, minWidth: Int = 0): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sourceW = bounds.outWidth
    val sourceH = bounds.outHeight
    if (sourceW < minWidth || sourceH <= 0 || sourceW < sourceH) return null

    val aspect = width.toFloat() / height
    var sample = 1
    while (min(sourceW.toFloat(), sourceH * aspect) / (sample * 2) >= width) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val s = decoded.width.toFloat() / sourceW

    // The largest region of the picture, inside any letterbox bars, with the target's shape.
    val bar = letterboxBar(decoded) / s
    val pictureH = sourceH - bar * 2
    val cropW = min(sourceW.toFloat(), pictureH * aspect)
    val cropH = cropW / aspect
    val scale = if (upscale) width / cropW else min(1f, width / cropW)
    val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
    val outH = (cropH * scale).roundToInt().coerceAtLeast(1)
    val left = (sourceW - cropW) / 2 * s
    val top = (bar + (pictureH - cropH) * 0.2f) * s
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        decoded,
        Rect(left.roundToInt(), top.roundToInt(), (left + cropW * s).roundToInt(), (top + cropH * s).roundToInt()),
        Rect(0, 0, outW, outH),
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
    )
    decoded.recycle()
    out.setHasAlpha(false)
    return out
}

/**
 * The height of the black bars baked into the top and bottom of [bitmap], or 0. YouTube's 4:3
 * thumbnails carry them. Needs a bar of about the same size at both ends, so a dark scene isn't
 * mistaken for one.
 */
private fun letterboxBar(bitmap: Bitmap): Int {
    fun isBlack(y: Int) = (1 until BAR_SAMPLES).all { i ->
        val c = bitmap.getPixel(bitmap.width * i / BAR_SAMPLES, y)
        maxOf(Color.red(c), Color.green(c), Color.blue(c)) < BAR_MAX_BRIGHTNESS
    }
    val limit = (bitmap.height * BAR_MAX_SHARE).toInt()
    val top = (0 until limit).firstOrNull { !isBlack(it) } ?: return 0
    val bottom = (0 until limit).firstOrNull { !isBlack(bitmap.height - 1 - it) } ?: return 0
    val bar = min(top, bottom)
    // A couple of rows past the edge, where compression smears the bar into the picture.
    return if (bar >= bitmap.height * BAR_MIN_SHARE) bar + 2 else 0
}

private const val BAR_SAMPLES = 16
private const val BAR_MAX_BRIGHTNESS = 20
private const val BAR_MIN_SHARE = 0.04f
private const val BAR_MAX_SHARE = 0.25f
