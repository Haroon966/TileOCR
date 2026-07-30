package com.paperpanorama.ocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.paperpanorama.ocr.R

/**
 * Paint OCR blocks onto a plain white page using Inter, fitting each block to its bbox.
 */
class CleanPageRenderer(context: Context) {
    private val regular: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.SANS_SERIF
    private val semibold: Typeface =
        ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD

    fun render(
        pageW: Int,
        pageH: Int,
        blocks: List<OcrBlock>,
        apiPageW: Int = 0,
        apiPageH: Int = 0,
        inkColor: Int = INK,
    ): Bitmap {
        val w = pageW.coerceAtLeast(1)
        val h = pageH.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        for (block in blocks) {
            if (!OcrLayoutMath.isDrawableTextBlock(block.type)) continue
            val box = OcrLayoutMath.normalizeBox(
                block.left, block.top, block.right, block.bottom,
                w, h, apiPageW, apiPageH,
            )
            val face = if (OcrLayoutMath.isTitleBlock(block.type)) semibold else regular
            drawBlock(canvas, block.text, box, face, inkColor)
        }
        return bmp
    }

    private fun drawBlock(
        canvas: Canvas,
        text: String,
        box: OcrLayoutMath.PixelBox,
        typeface: Typeface,
        inkColor: Int,
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            this.typeface = typeface
        }
        val maxFromHeight = (box.height * 0.95f).coerceAtLeast(8f)
        val size = OcrLayoutMath.fitFontSizePx(
            minPx = 6f,
            maxPx = maxFromHeight.coerceAtMost(box.width.toFloat()),
        ) { trial ->
            paint.textSize = trial
            val layout = buildLayout(text, paint, box.width)
            layout.height <= box.height
        }
        paint.textSize = size
        val layout = buildLayout(text, paint, box.width)
        canvas.save()
        canvas.translate(box.left.toFloat(), box.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    companion object {
        /** Design-system ink (#3C3D37). */
        const val INK = 0xFF3C3D37.toInt()
    }
}
