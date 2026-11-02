package app.lumen.core.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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

    // The largest region of the source with the target's shape.
    val aspect = width.toFloat() / height
    val cropW = min(sourceW.toFloat(), sourceH * aspect)
    val cropH = cropW / aspect
    val scale = if (upscale) width / cropW else min(1f, width / cropW)
    val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
    val outH = (cropH * scale).roundToInt().coerceAtLeast(1)

    var sample = 1
    while (cropW / (sample * 2) >= outW) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val s = decoded.width.toFloat() / sourceW
    val left = (sourceW - cropW) / 2 * s
    val top = (sourceH - cropH) * 0.2f * s
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
