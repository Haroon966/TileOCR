package com.paperpanorama.ocr.ocr

import kotlin.math.max
import kotlin.math.min

/** Pure helpers for bbox normalize + font-fit (JVM-testable). */
object OcrLayoutMath {

    data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * Map API coords into page pixel space.
     * If any coord is in (0,1] and max ≤ 1.5 → treat as normalized [0,1].
     * Otherwise treat as absolute pixels (optionally scaled if API page size ≠ render size).
     */
    fun normalizeBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pageW: Int,
        pageH: Int,
        apiPageW: Int = 0,
        apiPageH: Int = 0,
    ): PixelBox {
        val w = pageW.coerceAtLeast(1)
        val h = pageH.coerceAtLeast(1)
        val maxC = max(max(left, top), max(right, bottom))
        val looksNormalized = maxC <= 1.5f && right > left && bottom > top
        val (l, t, r, b) = if (looksNormalized) {
            listOf(left * w, top * h, right * w, bottom * h)
        } else if (apiPageW > 0 && apiPageH > 0 && (apiPageW != w || apiPageH != h)) {
            val sx = w.toFloat() / apiPageW
            val sy = h.toFloat() / apiPageH
            listOf(left * sx, top * sy, right * sx, bottom * sy)
        } else {
            listOf(left, top, right, bottom)
        }
        return PixelBox(
            left = l.roundClamp(0, w - 1),
            top = t.roundClamp(0, h - 1),
            right = r.roundClamp(1, w),
            bottom = b.roundClamp(1, h),
        ).let { box ->
            // Ensure right > left, bottom > top after clamp.
            PixelBox(
                left = box.left,
                top = box.top,
                right = max(box.right, box.left + 1),
                bottom = max(box.bottom, box.top + 1),
            )
        }
    }

    private fun Float.roundClamp(minV: Int, maxV: Int): Int =
        min(maxV, max(minV, kotlin.math.round(this).toInt()))

    /**
     * Binary-search largest font size (px) where [fits] returns true.
     * [fits] is typically “StaticLayout height ≤ boxH and no overflow”.
     */
    fun fitFontSizePx(
        minPx: Float = 6f,
        maxPx: Float = 200f,
        iterations: Int = 14,
        fits: (Float) -> Boolean,
    ): Float {
        var lo = minPx
        var hi = maxPx
        var best = minPx
        repeat(iterations) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    fun isDrawableTextBlock(type: String): Boolean {
        val t = type.lowercase()
        return t !in setOf("image", "equation", "figure", "chart")
    }

    fun isTitleBlock(type: String): Boolean {
        val t = type.lowercase()
        return t == "title" || t == "heading" || t == "header"
    }

    /**
     * Y positions for [lineCount] lines inside a box.
     * Uses [lineHeight] for each line; leftover vertical space is spread evenly between lines.
     */
    fun lineTops(boxTop: Int, boxHeight: Int, lineCount: Int, lineHeight: Float): FloatArray {
        if (lineCount <= 0) return FloatArray(0)
        if (lineCount == 1) return floatArrayOf(boxTop.toFloat())
        val used = lineHeight * lineCount
        val spare = (boxHeight - used).coerceAtLeast(0f)
        val gap = spare / (lineCount - 1)
        val step = lineHeight + gap
        return FloatArray(lineCount) { i -> boxTop + i * step }
    }

    /** Canvas size: prefer larger of local page vs API dims for sharper text. */
    fun renderSize(pageW: Int, pageH: Int, apiPageW: Int, apiPageH: Int): Pair<Int, Int> {
        val w = max(pageW.coerceAtLeast(1), apiPageW.coerceAtLeast(0))
        val h = max(pageH.coerceAtLeast(1), apiPageH.coerceAtLeast(0))
        return w to h
    }
}
