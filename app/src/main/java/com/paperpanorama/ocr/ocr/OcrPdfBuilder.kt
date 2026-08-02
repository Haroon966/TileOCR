package com.paperpanorama.ocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds a high-quality PDF from OCR blocks + original page image.
 *
 * Layout rules:
 *  - Text blocks  → rendered with clean Inter typeface, black on white, exact position.
 *  - Image/figure/equation/chart blocks → cropped pixel-for-pixel from the original image
 *    and placed at the same normalised position in the PDF.
 *  - Everything else (background doodles, margins, etc.) → white.
 *
 * PDF page is sized at [MIN_LONG_EDGE] px on the long side so it reads well on screen
 * and prints cleanly.
 */
object OcrPdfBuilder {

    private const val MIN_LONG_EDGE = 2480   // ~A4 @ 300 DPI long side

    data class PdfPageInput(
        val blocks: List<OcrBlock>,
        val apiPageW: Int,
        val apiPageH: Int,
        val originalImageFile: File,
    )

    fun build(
        blocks: List<OcrBlock>,
        apiPageW: Int,
        apiPageH: Int,
        originalImageFile: File,
        regularTypeface: Typeface,
        semiboldTypeface: Typeface,
        outFile: File,
    ) = buildMultiPage(
        pages = listOf(PdfPageInput(blocks, apiPageW, apiPageH, originalImageFile)),
        regularTypeface = regularTypeface,
        semiboldTypeface = semiboldTypeface,
        outFile = outFile,
    )

    fun buildMultiPage(
        pages: List<PdfPageInput>,
        regularTypeface: Typeface,
        semiboldTypeface: Typeface,
        outFile: File,
    ) {
        val doc = PdfDocument()
        pages.forEachIndexed { pageIdx, input ->
            val origBmp = loadScaled(input.originalImageFile, 3000)
            val srcW = if (input.apiPageW > 0) input.apiPageW else origBmp?.width ?: 1000
            val srcH = if (input.apiPageH > 0) input.apiPageH else origBmp?.height ?: 1414

            val scale = if (max(srcW, srcH) >= MIN_LONG_EDGE) 1f
            else MIN_LONG_EDGE.toFloat() / max(srcW, srcH)

            val pdfW = (srcW * scale).roundToInt()
            val pdfH = (srcH * scale).roundToInt()

            val pageInfo = PdfDocument.PageInfo.Builder(pdfW, pdfH, pageIdx + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            for (block in input.blocks) {
                val box = OcrLayoutMath.normalizeBox(
                    block.left, block.top, block.right, block.bottom,
                    pdfW, pdfH, input.apiPageW, input.apiPageH,
                )

                if (!OcrLayoutMath.isDrawableTextBlock(block.type)) {
                    if (origBmp != null) {
                        val bmpW = origBmp.width
                        val bmpH = origBmp.height
                        val srcRect = Rect(
                            ((box.left.toFloat() / pdfW) * bmpW).roundToInt().coerceIn(0, bmpW - 1),
                            ((box.top.toFloat() / pdfH) * bmpH).roundToInt().coerceIn(0, bmpH - 1),
                            ((box.right.toFloat() / pdfW) * bmpW).roundToInt().coerceIn(1, bmpW),
                            ((box.bottom.toFloat() / pdfH) * bmpH).roundToInt().coerceIn(1, bmpH),
                        )
                        val dstRect = RectF(
                            box.left.toFloat(), box.top.toFloat(),
                            box.right.toFloat(), box.bottom.toFloat(),
                        )
                        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                        canvas.drawBitmap(origBmp, srcRect, dstRect, paint)
                    }
                } else {
                    val cleaned = OcrTextClean.stripMarkdown(block.text)
                    if (cleaned.isBlank()) continue
                    val face = if (OcrLayoutMath.isTitleBlock(block.type)) semiboldTypeface else regularTypeface
                    drawTextBlock(canvas, cleaned, box, face)
                }
            }

            doc.finishPage(page)
            origBmp?.recycle()
        }
        outFile.outputStream().buffered().use { doc.writeTo(it) }
        doc.close()
    }

    // ── Text rendering ────────────────────────────────────────────────────────

    private fun drawTextBlock(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        typeface: Typeface,
    ) {
        val lines = OcrTextClean.lines(text).ifEmpty { listOf(text.trim()) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.typeface = typeface
        }
        val maxH = (box.height * 0.95f / lines.size.coerceAtLeast(1)).coerceAtLeast(8f)
        val fitSize = OcrLayoutMath.fitFontSizePx(
            minPx = 8f,
            maxPx = maxH.coerceAtMost(box.width.toFloat()),
        ) { trial ->
            paint.textSize = trial
            val lh = paint.fontSpacing
            if (lh * lines.size > box.height + 0.5f) return@fitFontSizePx false
            lines.all { paint.measureText(it) <= box.width + 0.5f }
        }
        paint.textSize = fitSize

        // Re-wrap lines that overflow at the final size
        val wrapped = lines.flatMap { line ->
            if (paint.measureText(line) <= box.width) listOf(line)
            else wrapLine(line, paint, box.width)
        }

        val finalSize = OcrLayoutMath.fitFontSizePx(minPx = 8f, maxPx = fitSize) { trial ->
            paint.textSize = trial
            paint.fontSpacing * wrapped.size <= box.height + 0.5f
        }
        paint.textSize = finalSize

        val lh = paint.fontSpacing
        val tops = OcrLayoutMath.lineTops(box.top, box.height, wrapped.size, lh)
        val fm = paint.fontMetrics
        for (i in wrapped.indices) {
            canvas.drawText(wrapped[i], box.left.toFloat(), tops[i] - fm.ascent, paint)
        }
    }

    private fun wrapLine(line: String, paint: TextPaint, width: Int): List<String> {
        val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(line)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) <= width) {
                cur = StringBuilder(trial)
            } else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(line) }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun loadScaled(file: File, maxEdge: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val longEdge = max(opts.outWidth, opts.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
