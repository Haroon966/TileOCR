package com.paperpanorama.ocr.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

object BitmapDecode {
    fun bounds(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    fun sampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        var long = max(width, height)
        while (long / sample > maxLongEdge) sample *= 2
        return sample.coerceAtLeast(1)
    }

    fun decodeDownsampled(path: String, maxLongEdge: Int): Bitmap? {
        val (w, h) = bounds(path) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(w, h, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}
