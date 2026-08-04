package com.paperpanorama.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.paperpanorama.ocr.R

/**
 * Paint OCR blocks onto a plain white page using Verdana (+ Arabic fallback).
 * Single-line boxes fill; multi-line use equal bands + fit-in-band (spatial, no balloon).
 */
class CleanPageRenderer(context: Context) {
    private val regular: Typeface = buildFace(context, R.font.verdana_regular, Typeface.SANS_SERIF)
    private val semibold: Typeface = buildFace(context, R.font.verdana_bold, Typeface.DEFAULT_BOLD)

    fun regularTypeface(): Typeface = regular
    fun semiboldTypeface(): Typeface = semibold

    fun render(
        pageW: Int,
        pageH: Int,
        blocks: List<OcrBlock>,
        apiPageW: Int = 0,
        apiPageH: Int = 0,
        inkColor: Int = INK,
        /** Full-page markdown — when it looks like a plan diagram, use structured layout. */
        markdown: String = "",
    ): Bitmap {
        val (w, h) = OcrLayoutMath.renderSize(pageW, pageH, apiPageW, apiPageH)
        val planText = markdown.ifBlank {
            blocks.joinToString("\n") { it.text }
        }
        PlanDiagramParse.tryParse(planText)?.let { plan ->
            return PlanDiagramRenderer.render(w, h, plan, regular, semibold, inkColor)
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        for (block in blocks) {
            if (!OcrLayoutMath.isDrawableTextBlock(block.type)) continue
            val cleaned = OcrLayoutMath.stretchText(
                OcrTextClean.fixCommonTypos(OcrTextClean.stripMarkdown(block.text)),
            )
            if (cleaned.isBlank()) continue
            val box = OcrLayoutMath.normalizeBox(
                block.left, block.top, block.right, block.bottom,
                w, h, apiPageW, apiPageH,
            )
            val face = if (OcrLayoutMath.isTitleBlock(block.type)) semibold else regular
            OcrStretchDraw.drawStretchedBlock(
                canvas, cleaned, box, face, inkColor, blockType = block.type,
            )
        }
        return bmp
    }

    companion object {
        /** Design-system ink (#3C3D37). */
        const val INK = 0xFF3C3D37.toInt()

        private fun buildFace(context: Context, primaryRes: Int, fallback: Typeface): Typeface {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return try {
                    val primaryFamily = FontFamily.Builder(
                        Font.Builder(context.resources, primaryRes).build(),
                    ).build()
                    val arabicFamily = FontFamily.Builder(
                        Font.Builder(context.resources, R.font.noto_sans_arabic_regular).build(),
                    ).build()
                    Typeface.CustomFallbackBuilder(primaryFamily)
                        .addCustomFallback(arabicFamily)
                        .setSystemFallback("sans-serif")
                        .build()
                } catch (_: Throwable) {
                    ResourcesCompat.getFont(context, primaryRes) ?: fallback
                }
            }
            return ResourcesCompat.getFont(context, primaryRes) ?: fallback
        }
    }
}
