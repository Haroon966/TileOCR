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
 *  - Text blocks  → Verdana (or provided faces) stretched into each bbox, black on white.
 *  - Image/figure/equation/chart (+ residual) → cropped from the original image at same box.
 *  - Everything else → white.
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
            val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

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
                        canvas.drawBitmap(origBmp, srcRect, dstRect, bmpPaint)
                    }
                } else {
                    val cleaned = OcrLayoutMath.stretchText(OcrTextClean.stripMarkdown(block.text))
                    if (cleaned.isBlank()) continue
                    val face = if (OcrLayoutMath.isTitleBlock(block.type)) semiboldTypeface else regularTypeface
                    OcrStretchDraw.drawStretchedBlock(
                        canvas, cleaned, box, face, Color.BLACK, blockType = block.type,
                    )
                }
            }

            doc.finishPage(page)
            origBmp?.recycle()
        }
        outFile.outputStream().buffered().use { doc.writeTo(it) }
        doc.close()
    }

    // ── Searchable PDF: original image visible, OCR text invisible on top ─────

    data class SearchablePageInput(
        val blocks: List<OcrBlock>,
        val apiPageW: Int,
        val apiPageH: Int,
        val originalImageFile: File,
    )

    fun buildSearchable(
        blocks: List<OcrBlock>,
        apiPageW: Int,
        apiPageH: Int,
        originalImageFile: File,
        outFile: File,
    ) = buildSearchableMultiPage(
        pages = listOf(SearchablePageInput(blocks, apiPageW, apiPageH, originalImageFile)),
        outFile = outFile,
    )

    /**
     * Page = the original scan pixel-for-pixel (visible), with each OCR word drawn again on top
     * at zero alpha, stretched to its own bounding box. The glyphs are real PDF text — searchable
     * and selectable — but fully transparent, so the page still looks exactly like the scan.
     */
    fun buildSearchableMultiPage(pages: List<SearchablePageInput>, outFile: File) {
        val doc = PdfDocument()
        pages.forEachIndexed { pageIdx, input ->
            val origBmp = loadScaled(input.originalImageFile, 3000) ?: return@forEachIndexed
            val pdfW = origBmp.width
            val pdfH = origBmp.height

            val pageInfo = PdfDocument.PageInfo.Builder(pdfW, pdfH, pageIdx + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(
                origBmp,
                Rect(0, 0, pdfW, pdfH),
                RectF(0f, 0f, pdfW.toFloat(), pdfH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )

            val invisible = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 0 }
            for (block in input.blocks) {
                val text = OcrLayoutMath.stretchText(block.text)
                if (text.isEmpty()) continue
                val box = OcrLayoutMath.normalizeBox(
                    block.left, block.top, block.right, block.bottom,
                    pdfW, pdfH, input.apiPageW, input.apiPageH,
                )
                OcrStretchDraw.drawStretchedLine(canvas, text, box, invisible)
            }

            doc.finishPage(page)
            origBmp.recycle()
        }
        outFile.outputStream().buffered().use { doc.writeTo(it) }
        doc.close()
    }

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
