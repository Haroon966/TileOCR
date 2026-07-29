package com.paperpanorama.ocr.doc

import com.paperpanorama.ocr.domain.DocQuad
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure quad geometry — JVM-testable. */
object QuadMath {
    fun fullFrame(width: Int, height: Int, insetFrac: Float = 0.02f): DocQuad {
        val ix = width * insetFrac
        val iy = height * insetFrac
        return DocQuad(
            tlX = ix,
            tlY = iy,
            trX = width - ix,
            trY = iy,
            brX = width - ix,
            brY = height - iy,
            blX = ix,
            blY = height - iy,
        )
    }

    /**
     * Order four unordered points into TL, TR, BR, BL using sum/diff heuristic.
     */
    fun orderCorners(raw: List<Pair<Float, Float>>): DocQuad {
        require(raw.size == 4) { "Need 4 points" }
        val tl = raw.minBy { it.first + it.second }
        val br = raw.maxBy { it.first + it.second }
        val tr = raw.minBy { -it.first + it.second }
        val bl = raw.maxBy { -it.first + it.second }
        return DocQuad(
            tlX = tl.first,
            tlY = tl.second,
            trX = tr.first,
            trY = tr.second,
            brX = br.first,
            brY = br.second,
            blX = bl.first,
            blY = bl.second,
        )
    }

    fun scale(quad: DocQuad, sx: Float, sy: Float): DocQuad = DocQuad(
        tlX = quad.tlX * sx,
        tlY = quad.tlY * sy,
        trX = quad.trX * sx,
        trY = quad.trY * sy,
        brX = quad.brX * sx,
        brY = quad.brY * sy,
        blX = quad.blX * sx,
        blY = quad.blY * sy,
    )

    /** Map quad after 90° clockwise image rotation (oldW×oldH → oldH×oldW). */
    fun rotate90Cw(quad: DocQuad, oldWidth: Int, oldHeight: Int): DocQuad {
        fun map(x: Float, y: Float): Pair<Float, Float> =
            (oldHeight - y) to x
        return orderCorners(quad.points().map { (x, y) -> map(x, y) })
    }

    fun edgeLengths(quad: DocQuad): Pair<Float, Float> {
        val top = hypot(quad.trX - quad.tlX, quad.trY - quad.tlY)
        val bottom = hypot(quad.brX - quad.blX, quad.brY - quad.blY)
        val left = hypot(quad.blX - quad.tlX, quad.blY - quad.tlY)
        val right = hypot(quad.brX - quad.trX, quad.brY - quad.trY)
        val outW = max(top, bottom)
        val outH = max(left, right)
        return outW to outH
    }

    /** Push corners outward from the centroid — safety margin so edge glyphs survive the crop. */
    fun expand(quad: DocQuad, frac: Float): DocQuad {
        val pts = quad.points()
        val cx = pts.map { it.first }.average().toFloat()
        val cy = pts.map { it.second }.average().toFloat()
        val k = 1f + frac
        return DocQuad(
            tlX = cx + (quad.tlX - cx) * k,
            tlY = cy + (quad.tlY - cy) * k,
            trX = cx + (quad.trX - cx) * k,
            trY = cy + (quad.trY - cy) * k,
            brX = cx + (quad.brX - cx) * k,
            brY = cy + (quad.brY - cy) * k,
            blX = cx + (quad.blX - cx) * k,
            blY = cy + (quad.blY - cy) * k,
        )
    }

    fun clampToImage(quad: DocQuad, width: Int, height: Int): DocQuad {
        fun c(v: Float, maxV: Int) = v.coerceIn(0f, maxV - 1f)
        return DocQuad(
            tlX = c(quad.tlX, width),
            tlY = c(quad.tlY, height),
            trX = c(quad.trX, width),
            trY = c(quad.trY, height),
            brX = c(quad.brX, width),
            brY = c(quad.brY, height),
            blX = c(quad.blX, width),
            blY = c(quad.blY, height),
        )
    }

    fun area(quad: DocQuad): Float {
        // Shoelace
        val pts = quad.points()
        var sum = 0f
        for (i in pts.indices) {
            val (x1, y1) = pts[i]
            val (x2, y2) = pts[(i + 1) % pts.size]
            sum += x1 * y2 - x2 * y1
        }
        return kotlin.math.abs(sum) / 2f
    }

    fun minDim(width: Int, height: Int) = min(width, height)
}
