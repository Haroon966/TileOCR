package com.paperpanorama.ocr.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.min

/**
 * Renders [PlanDiagramParse.PlanDiagram] like the gold last-page layout:
 * flowers, task→arrow, owner columns, UI 3-branch tree, numbered rows with right brackets.
 */
object PlanDiagramRenderer {

    fun render(
        pageW: Int,
        pageH: Int,
        plan: PlanDiagramParse.PlanDiagram,
        regular: Typeface,
        bold: Typeface,
        ink: Int = CleanPageRenderer.INK,
    ): Bitmap {
        val w = pageW.coerceAtLeast(1)
        val h = pageH.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = (min(w, h) * 0.0025f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.FILL
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = regular
        }
        val textBold = TextPaint(text).apply { typeface = bold }

        fun tx(p: TextPaint, s: String, x: Float, y: Float, size: Float) {
            p.textSize = size
            p.textScaleX = 1f
            c.drawText(s, x, y, p)
        }

        fun measure(p: TextPaint, s: String, size: Float): Float {
            p.textSize = size
            return p.measureText(s)
        }

        val m = w * 0.06f
        val titleSize = h * 0.028f
        val bodySize = h * 0.022f
        val smallSize = h * 0.018f

        // Title top-center underlined
        val titleW = measure(textBold, plan.title, titleSize)
        val titleX = (w - titleW) / 2f
        val titleBaseline = h * 0.045f
        tx(textBold, plan.title, titleX, titleBaseline, titleSize)
        c.drawLine(titleX, titleBaseline + h * 0.006f, titleX + titleW, titleBaseline + h * 0.006f, stroke)

        // Flower 1 (top-left) — left of Task
        drawFlower(c, w * 0.07f, h * 0.105f, w * 0.038f, stroke, fill)

        // Task → ...
        val taskY = h * 0.12f
        val taskLabel = plan.taskArrow.substringBefore("->").trim().ifBlank { "Task" }
        val taskRest = plan.taskArrow.substringAfter("->", plan.taskArrow).trim()
        tx(textBold, taskLabel, w * 0.14f, taskY, bodySize)
        val taskLabelW = measure(textBold, taskLabel, bodySize)
        val arrowX0 = w * 0.14f + taskLabelW + w * 0.012f
        val arrowX1 = w * 0.38f
        val arrowY = taskY - bodySize * 0.35f
        drawArrowRight(c, arrowX0, arrowY, arrowX1, stroke)
        tx(text, taskRest, arrowX1 + w * 0.02f, taskY, bodySize)

        // Owners: 3 down-arrows under task phrase
        val ownersY = h * 0.195f
        tx(textBold, plan.ownersLabel, m, ownersY, smallSize)
        val phraseX0 = arrowX1 + w * 0.02f
        val phraseW = measure(text, taskRest, bodySize)
        val colXs = floatArrayOf(
            phraseX0 + phraseW * 0.18f,
            phraseX0 + phraseW * 0.50f,
            phraseX0 + phraseW * 0.82f,
        )
        val parentY = taskY + bodySize * 0.25f
        for (i in 0 until min(3, plan.ownerCols.size)) {
            val cx = colXs[i].coerceIn(w * 0.26f, w * 0.88f)
            c.drawLine(cx, parentY, cx, ownersY - smallSize * 0.55f, stroke)
            drawArrowDown(c, cx, ownersY - smallSize * 0.55f, stroke)
            val col = plan.ownerCols[i]
            val tw = measure(text, col.nameDays, smallSize)
            tx(text, col.nameDays, cx - tw / 2f, ownersY, smallSize)
            col.sub?.let { sub ->
                val sw = measure(text, sub, smallSize * 0.92f)
                tx(text, sub, cx - sw / 2f, ownersY + smallSize * 1.35f, smallSize * 0.92f)
            }
        }

        // Dependencies + Plan + flower 2
        tx(textBold, plan.dependencies, m, h * 0.28f, bodySize)
        drawFlowerStem(c, w * 0.12f, h * 0.36f, w * 0.04f, stroke)
        tx(textBold, plan.planLabel, w * 0.18f, h * 0.36f, bodySize)

        // UI tree
        val uiY = h * 0.33f
        val uiW = measure(textBold, plan.uiTitle, bodySize)
        tx(textBold, plan.uiTitle, (w - uiW) / 2f, uiY, bodySize)
        val webW = measure(text, plan.webApp, smallSize)
        tx(text, plan.webApp, (w - webW) / 2f, uiY + bodySize * 1.2f, smallSize)

        val treeTop = uiY + bodySize * 1.5f
        val treeBot = h * 0.48f
        val midX = w * 0.5f
        val bxs = floatArrayOf(w * 0.25f, w * 0.50f, w * 0.75f)
        c.drawLine(bxs[0], treeTop, bxs[2], treeTop, stroke)
        c.drawLine(midX, uiY + bodySize * 1.35f, midX, treeTop, stroke)
        val branches = listOf(plan.branchLeft, plan.branchMid, plan.branchRight)
        for (i in 0..2) {
            c.drawLine(bxs[i], treeTop, bxs[i], treeBot - bodySize, stroke)
            drawArrowDown(c, bxs[i], treeBot - bodySize, stroke)
            drawWrappedCenter(c, text, branches[i], bxs[i], treeBot, smallSize, w * 0.22f)
        }

        // Numbered rows
        var rowY = h * 0.58f
        val idxX = m
        val leftX = w * 0.14f
        val rightX = w * 0.78f
        for (row in plan.rows) {
            if (row.index == "5") continue // footer owns row 5
            tx(textBold, "${row.index}-", idxX, rowY, bodySize)
            if (row.left.contains("Parallel", ignoreCase = true) && row.forkBelow != null) {
                val parallel = "Parallel"
                tx(text, parallel, leftX, rowY, bodySize)
                val pw = measure(text, parallel, bodySize)
                val braceX = leftX + pw + w * 0.018f
                val topY = rowY - bodySize * 0.85f
                val botY = rowY + bodySize * 0.95f
                drawCurlyBrace(c, braceX, topY, botY, w * 0.028f, stroke)
                val afterArrow = row.left.substringAfter("->", "Translation").trim()
                    .ifBlank { "Translation" }
                val textX = braceX + w * 0.035f
                tx(text, afterArrow, textX, topY + bodySize * 0.35f, bodySize)
                var forkText = row.forkBelow
                val forkBracket = Regex("""\[([^\]]+)\]""").find(forkText)
                if (forkBracket != null) {
                    forkText = forkText.replace(forkBracket.value, "").trim()
                }
                tx(text, forkText.ifBlank { "Mapping" }, textX, botY - bodySize * 0.15f, bodySize)
                row.rightBracket?.let { tx(text, it, rightX, rowY, bodySize) }
                rowY += bodySize * 2.55f
            } else {
                tx(text, row.left, leftX, rowY, bodySize)
                row.rightBracket?.let { tx(text, it, rightX, rowY, bodySize) }
                rowY += bodySize * 1.55f
            }
        }

        plan.footer?.let { foot ->
            val fy = (rowY + h * 0.01f).coerceAtMost(h * 0.94f)
            // Gold: 5- [ 3, 7 ] Web App (Regression...)
            val bm = Regex("""^(\[[^\]]+\])\s*(.*)$""").find(foot.trim())
            tx(textBold, "5-", idxX, fy, bodySize)
            if (bm != null) {
                tx(text, bm.groupValues[1], leftX, fy, bodySize)
                val bx = leftX + measure(text, bm.groupValues[1], bodySize) + w * 0.02f
                drawWrappedLeft(c, text, bm.groupValues[2], bx, fy, smallSize, w * 0.55f)
            } else {
                drawWrappedLeft(c, text, foot, leftX, fy, smallSize, w * 0.7f)
            }
        }

        return bmp
    }

    private fun drawCurlyBrace(
        c: Canvas,
        x: Float,
        top: Float,
        bot: Float,
        depth: Float,
        stroke: Paint,
    ) {
        val mid = (top + bot) / 2f
        val path = Path()
        // Opening brace `{` facing right (content to the right of brace)
        path.moveTo(x + depth, top)
        path.cubicTo(x, top, x, mid - depth * 0.6f, x + depth * 0.35f, mid)
        path.cubicTo(x, mid + depth * 0.6f, x, bot, x + depth, bot)
        c.drawPath(path, stroke)
    }

    private fun drawWrappedCenter(
        c: Canvas,
        paint: TextPaint,
        text: String,
        cx: Float,
        top: Float,
        size: Float,
        maxW: Float,
    ) {
        paint.textSize = size
        val words = text.split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) <= maxW) cur = StringBuilder(trial)
            else {
                if (cur.isNotEmpty()) lines.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        var y = top
        for (line in lines) {
            val tw = paint.measureText(line)
            c.drawText(line, cx - tw / 2f, y, paint)
            y += size * 1.15f
        }
    }

    private fun drawWrappedLeft(
        c: Canvas,
        paint: TextPaint,
        text: String,
        x: Float,
        top: Float,
        size: Float,
        maxW: Float,
    ) {
        paint.textSize = size
        val words = text.split(Regex("\\s+"))
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(trial) <= maxW) cur = StringBuilder(trial)
            else {
                if (cur.isNotEmpty()) lines.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        var y = top
        for (line in lines) {
            c.drawText(line, x, y, paint)
            y += size * 1.2f
        }
    }

    private fun drawArrowRight(c: Canvas, x0: Float, y: Float, x1: Float, stroke: Paint) {
        c.drawLine(x0, y, x1, y, stroke)
        val ah = stroke.strokeWidth * 4f
        c.drawLine(x1, y, x1 - ah, y - ah * 0.6f, stroke)
        c.drawLine(x1, y, x1 - ah, y + ah * 0.6f, stroke)
    }

    private fun drawArrowDown(c: Canvas, x: Float, y: Float, stroke: Paint) {
        val ah = stroke.strokeWidth * 4f
        c.drawLine(x, y, x - ah * 0.6f, y - ah, stroke)
        c.drawLine(x, y, x + ah * 0.6f, y - ah, stroke)
    }

    /** Simple 5-petal flower (gold style). */
    private fun drawFlower(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Paint, fill: Paint) {
        for (i in 0 until 5) {
            val a = Math.toRadians((-90 + i * 72).toDouble())
            val px = (cx + Math.cos(a) * r * 0.72).toFloat()
            val py = (cy + Math.sin(a) * r * 0.72).toFloat()
            c.drawCircle(px, py, r * 0.42f, stroke)
        }
        c.drawCircle(cx, cy, r * 0.28f, fill)
    }

    private fun drawFlowerStem(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Paint) {
        val fill = Paint(stroke).apply { style = Paint.Style.FILL }
        drawFlower(c, cx, cy - r * 0.6f, r * 0.85f, stroke, fill)
        c.drawLine(cx, cy, cx, cy + r * 1.6f, stroke)
        val leaf = Path()
        leaf.moveTo(cx, cy + r * 0.5f)
        leaf.quadTo(cx - r * 1.1f, cy + r, cx, cy + r * 1.2f)
        c.drawPath(leaf, stroke)
        val leaf2 = Path()
        leaf2.moveTo(cx, cy + r * 0.7f)
        leaf2.quadTo(cx + r * 1.1f, cy + r * 1.1f, cx, cy + r * 1.4f)
        c.drawPath(leaf2, stroke)
    }
}
