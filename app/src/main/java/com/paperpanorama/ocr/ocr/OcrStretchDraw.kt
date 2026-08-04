package com.paperpanorama.ocr.ocr

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Draw OCR text into bboxes with Verdana:
 * - [WORD_TYPE] → fill box (Vision words).
 * - [LINE_TYPE] / other single-line → fit in box (left-aligned, gold last_clean look).
 * - Multi-line (unexpanded) → equal bands + fit-in-band.
 */
object OcrStretchDraw {

    fun drawStretchedBlock(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        typeface: Typeface,
        color: Int,
        alpha: Int = 255,
        blockType: String = "text",
    ) {
        val lines = OcrLayoutMath.stretchLines(text)
        if (lines.isEmpty()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.typeface = typeface
            this.alpha = alpha.coerceIn(0, 255)
        }
        val isWord = blockType.equals(OcrLayoutMath.WORD_TYPE, ignoreCase = true)
        if (lines.size == 1) {
            if (isWord) {
                drawFilledLine(canvas, lines[0], box, paint)
            } else {
                drawFitInBand(canvas, lines[0], box, paint)
            }
            return
        }
        drawBandedFitLines(canvas, lines, box, paint)
    }

    fun drawStretchedLine(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        paint: TextPaint,
    ) = drawFilledLine(canvas, text, box, paint)

    /** Single glyph run stretched to fill [box] (Vision words). */
    private fun drawFilledLine(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        paint: TextPaint,
    ) {
        if (text.isEmpty()) return
        paint.textScaleX = 1f
        paint.textSize = box.height.toFloat().coerceAtLeast(4f)
        val measured = paint.measureText(text)
        paint.textScaleX = OcrLayoutMath.textStretchScaleX(box.width.toFloat(), measured)
        val baseline = box.top - paint.fontMetrics.ascent
        canvas.drawText(text, box.left.toFloat(), baseline, paint)
    }

    private fun drawBandedFitLines(
        canvas: Canvas,
        lines: List<String>,
        box: OcrLayoutMath.PixelBox,
        paint: TextPaint,
    ) {
        val bands = OcrLayoutMath.equalLineBands(box, lines.size)
        for (i in lines.indices) {
            drawFitInBand(canvas, lines[i], bands[i], paint)
        }
    }

    private fun drawFitInBand(
        canvas: Canvas,
        text: String,
        band: OcrLayoutMath.PixelBox,
        paint: TextPaint,
    ) {
        if (text.isEmpty()) return
        paint.textScaleX = 1f
        val size = OcrLayoutMath.fitPackedFontSizePx(
            boxWidth = band.width.toFloat(),
            boxHeight = band.height.toFloat(),
            lineCount = 1,
            measureWidth = { trial ->
                paint.textSize = trial
                paint.measureText(text)
            },
            lineHeight = { trial ->
                paint.textSize = trial
                paint.fontSpacing
            },
        )
        paint.textSize = size
        val measured = paint.measureText(text)
        paint.textScaleX = OcrLayoutMath.textFitScaleX(band.width.toFloat(), measured)
        val fm = paint.fontMetrics
        val glyphH = fm.descent - fm.ascent
        val top = band.top + ((band.height - glyphH) / 2f).coerceAtLeast(0f)
        val baseline = top - fm.ascent
        canvas.drawText(text, band.left.toFloat(), baseline, paint)
    }
}
