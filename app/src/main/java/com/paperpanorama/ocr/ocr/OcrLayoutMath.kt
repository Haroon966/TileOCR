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

    /**
     * Prepare OCR text for stretch-into-bbox draw: keep newlines, collapse spaces
     * within each line only. Does not wrap or invent layout.
     */
    fun stretchText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { line ->
                line.replace(Regex("[ \\t]+"), " ").trim()
            }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Horizontal scale so measured text width fills [boxW] (may expand).
     * Returns 1 when measured is empty/zero; never Inf/NaN.
     */
    fun textStretchScaleX(boxW: Float, measuredW: Float): Float {
        val bw = boxW.coerceAtLeast(1f)
        if (measuredW <= 0f || !measuredW.isFinite()) return 1f
        val scale = bw / measuredW
        return if (scale.isFinite() && scale > 0f) scale else 1f
    }

    /** Shrink-only scale: never expand past natural glyph width. */
    fun textFitScaleX(boxW: Float, measuredW: Float): Float =
        textStretchScaleX(boxW, measuredW).coerceAtMost(1f)

    /** Non-empty lines after [stretchText] — OCR newlines only, no wrap. */
    fun stretchLines(raw: String): List<String> =
        stretchText(raw)
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Equal-height vertical bands for [lineCount] OCR lines inside a box.
     * Used for single-line fill path / searchable word boxes.
     */
    fun equalLineBands(box: PixelBox, lineCount: Int): List<PixelBox> {
        if (lineCount <= 0) return emptyList()
        if (lineCount == 1) return listOf(box)
        val bandH = box.height / lineCount
        return List(lineCount) { i ->
            val top = box.top + i * bandH
            val bottom = if (i == lineCount - 1) box.bottom else top + bandH
            PixelBox(box.left, top, box.right, max(bottom, top + 1))
        }
    }

    /**
     * Split multi-line text blocks into one block per OCR line, carving equal
     * vertical bands from the parent box (same coords space as input — norm or px).
     * Types become [LINE_TYPE] so render fits (left-aligned) instead of full-box stretch.
     * Non-text / single-line / [WORD_TYPE] blocks pass through.
     */
    fun expandMultilineToLineBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.isEmpty()) return blocks
        val out = ArrayList<OcrBlock>(blocks.size * 2)
        for (block in blocks) {
            if (!isDrawableTextBlock(block.type) || block.type.equals(WORD_TYPE, ignoreCase = true)) {
                out.add(block)
                continue
            }
            val lines = stretchLines(block.text)
            if (lines.size <= 1) {
                out.add(block)
                continue
            }
            val span = block.bottom - block.top
            val band = span / lines.size
            lines.forEachIndexed { i, line ->
                val top = block.top + i * band
                val bottom = if (i == lines.size - 1) block.bottom else top + band
                out.add(
                    OcrBlock(
                        type = LINE_TYPE,
                        text = line,
                        left = block.left,
                        top = top,
                        right = block.right,
                        bottom = bottom,
                    ),
                )
            }
        }
        return out
    }

    const val WORD_TYPE = "word"
    const val LINE_TYPE = "line"

    /**
     * Largest textSize where multi-line OCR content packs into [box] without wrap:
     * each line natural width (shrink-only) fits boxW, and N * lineHeight fits boxH.
     */
    fun fitPackedFontSizePx(
        boxWidth: Float,
        boxHeight: Float,
        lineCount: Int,
        minPx: Float = 6f,
        measureWidth: (Float) -> Float,
        lineHeight: (Float) -> Float,
    ): Float {
        if (lineCount <= 0) return minPx
        val maxFromH = (boxHeight / lineCount).coerceAtLeast(minPx)
        return fitFontSizePx(minPx = minPx, maxPx = maxFromH.coerceAtLeast(minPx)) { trial ->
            val lh = lineHeight(trial)
            if (lh * lineCount > boxHeight + 0.5f) return@fitFontSizePx false
            measureWidth(trial) <= boxWidth + 0.5f
        }
    }
}
