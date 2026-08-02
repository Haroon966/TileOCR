package com.paperpanorama.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.paperpanorama.ocr.R

/**
 * Paint OCR blocks onto a plain white page using Inter (+ Arabic fallback),
 * packing lines into each block bbox.
 */
class CleanPageRenderer(context: Context) {
    private val regular: Typeface = buildFace(context, R.font.inter_regular, Typeface.SANS_SERIF)
    private val semibold: Typeface = buildFace(context, R.font.inter_semibold, Typeface.DEFAULT_BOLD)

    fun regularTypeface(): Typeface = regular
    fun semiboldTypeface(): Typeface = semibold

    fun render(
        pageW: Int,
        pageH: Int,
        blocks: List<OcrBlock>,
        apiPageW: Int = 0,
        apiPageH: Int = 0,
        inkColor: Int = INK,
    ): Bitmap {
        val (w, h) = OcrLayoutMath.renderSize(pageW, pageH, apiPageW, apiPageH)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        for (block in blocks) {
            if (!OcrLayoutMath.isDrawableTextBlock(block.type)) continue
            val cleaned = OcrTextClean.stripMarkdown(block.text)
            if (cleaned.isBlank()) continue
            val box = OcrLayoutMath.normalizeBox(
                block.left, block.top, block.right, block.bottom,
                w, h, apiPageW, apiPageH,
            )
            val face = if (OcrLayoutMath.isTitleBlock(block.type)) semibold else regular
            drawBlockLines(canvas, cleaned, box, face, inkColor)
        }
        return bmp
    }

    private fun drawBlockLines(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        typeface: Typeface,
        inkColor: Int,
    ) {
        val lines = OcrTextClean.lines(text).ifEmpty { listOf(text.trim()) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            this.typeface = typeface
        }
        val maxFromHeight = (box.height * 0.95f / lines.size.coerceAtLeast(1)).coerceAtLeast(8f)
        val size = OcrLayoutMath.fitFontSizePx(
            minPx = 6f,
            maxPx = maxFromHeight.coerceAtMost(box.width.toFloat()),
        ) { trial ->
            paint.textSize = trial
            val lh = paint.fontSpacing
            if (lh * lines.size > box.height + 0.5f) return@fitFontSizePx false
            lines.all { paint.measureText(it) <= box.width + 0.5f }
        }
        paint.textSize = size

        val drawn = ArrayList<String>()
        for (line in lines) {
            if (paint.measureText(line) <= box.width) {
                drawn.add(line)
            } else {
                drawn.addAll(wrapLine(line, paint, box.width))
            }
        }
        val finalSize = OcrLayoutMath.fitFontSizePx(
            minPx = 6f,
            maxPx = size,
        ) { trial ->
            paint.textSize = trial
            paint.fontSpacing * drawn.size <= box.height + 0.5f
        }
        paint.textSize = finalSize
        val lh = paint.fontSpacing
        val tops = OcrLayoutMath.lineTops(box.top, box.height, drawn.size, lh)
        val fm = paint.fontMetrics
        for (i in drawn.indices) {
            val baseline = tops[i] - fm.ascent
            canvas.drawText(drawn[i], box.left.toFloat(), baseline, paint)
        }
    }

    private fun wrapLine(line: String, paint: TextPaint, width: Int): List<String> {
        if (line.isEmpty()) return listOf("")
        val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(line)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) <= width) {
                cur = StringBuilder(trial)
            } else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                if (paint.measureText(w) <= width) {
                    cur = StringBuilder(w)
                } else {
                    var rest = w
                    while (rest.isNotEmpty()) {
                        var cut = rest.length
                        while (cut > 1 && paint.measureText(rest.substring(0, cut)) > width) {
                            cut--
                        }
                        out.add(rest.substring(0, cut))
                        rest = rest.substring(cut)
                    }
                    cur = StringBuilder()
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(line) }
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
