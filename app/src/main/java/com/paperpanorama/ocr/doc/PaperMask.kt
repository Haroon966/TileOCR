package com.paperpanorama.ocr.doc

import com.paperpanorama.ocr.domain.DocQuad
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Segments the dominant sheet of paper in a BGR image: low-saturation + bright
 * pixels, strong open to sever bridges to bright background patterns or skin,
 * largest connected blob, interior holes filled (dense ink/barcodes are dark and
 * would otherwise punch through), small dilation as a safety margin.
 */
object PaperMask {

    /** 255-on-paper binary mask (same size as input), or null when no credible paper blob exists. */
    fun paperBlob(bgr: Mat): Mat? {
        // Segment at ~1000px: morphology cost drops ~5x and kernels stay at tuned sizes.
        val longEdge = max(bgr.cols(), bgr.rows())
        if (longEdge > WORK_EDGE) {
            val s = WORK_EDGE.toDouble() / longEdge
            val small = Mat()
            Imgproc.resize(bgr, small, Size(bgr.cols() * s, bgr.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            val smallMask = paperBlob(small)
            small.release()
            if (smallMask == null) return null
            val full = Mat()
            Imgproc.resize(smallMask, full, Size(bgr.cols().toDouble(), bgr.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            smallMask.release()
            return full
        }
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = ArrayList<Mat>(3)
        Core.split(hsv, channels)
        hsv.release()
        val lowSat = Mat()
        Imgproc.threshold(channels[1], lowSat, MAX_SAT, 255.0, Imgproc.THRESH_BINARY_INV)
        val bright = Mat()
        Imgproc.threshold(channels[2], bright, MIN_VAL, 255.0, Imgproc.THRESH_BINARY)
        channels.forEach { it.release() }
        val mask = Mat()
        Core.bitwise_and(lowSat, bright, mask)
        lowSat.release()
        bright.release()

        // Kernel sizes were tuned at a 1000px long edge; scale for other sizes.
        val scale = max(bgr.cols(), bgr.rows()) / 1000.0
        morph(mask, Imgproc.MORPH_OPEN, kernelPx(35, scale))
        morph(mask, Imgproc.MORPH_CLOSE, kernelPx(15, scale))
        return refineBlob(mask)
    }

    /**
     * Shared blob post-processing: largest connected component, minimum-area
     * sanity check, interior hole filling, small dilation safety margin.
     * Consumes (releases) [mask]. Returns null when no credible blob exists.
     */
    fun refineBlob(mask: Mat): Mat? {
        val cols = mask.cols()
        val rows = mask.rows()
        val scale = max(cols, rows) / 1000.0
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        centroids.release()
        mask.release()
        if (n < 2) {
            labels.release()
            stats.release()
            return null
        }
        var bestLabel = -1
        var bestArea = 0.0
        for (i in 1 until n) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area > bestArea) {
                bestArea = area
                bestLabel = i
            }
        }
        stats.release()
        val total = (cols.toLong() * rows).toDouble()
        if (bestLabel < 0 || bestArea < MIN_BLOB_FRAC * total) {
            labels.release()
            return null
        }

        val blob = Mat()
        Core.compare(labels, Scalar(bestLabel.toDouble()), blob, Core.CMP_EQ)
        labels.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(blob, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        blob.setTo(Scalar(0.0))
        Imgproc.drawContours(blob, contours, -1, Scalar(255.0), Imgproc.FILLED)
        contours.forEach { it.release() }

        val dilate = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelPx(20, scale), kernelPx(20, scale)))
        Imgproc.dilate(blob, blob, dilate)
        dilate.release()
        return blob
    }

    /**
     * Largest blob in a binary mask → convex hull → 4-corner quad (minAreaRect
     * fallback). Returns null when the hull swallows nearly the whole frame
     * (contaminated segmentation). Coordinates are in mask space.
     */
    fun quadFromBlob(blob: Mat): DocQuad? {
        val w = blob.cols()
        val h = blob.rows()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(blob, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        if (contours.isEmpty()) return null

        val largest = contours.maxBy { Imgproc.contourArea(it) }
        val hullIdx = MatOfInt()
        Imgproc.convexHull(largest, hullIdx)
        val pts = largest.toArray()
        val hullPts = hullIdx.toArray().map { pts[it] }
        hullIdx.release()
        val hull2f = MatOfPoint2f(*hullPts.toTypedArray())

        // Hull swallowing the whole frame means contamination — bail to fallback.
        val hullPoly = MatOfPoint(*hullPts.toTypedArray())
        val hullArea = Imgproc.contourArea(hullPoly)
        hullPoly.release()
        if (hullArea > 0.95 * w * h) {
            hull2f.release()
            contours.forEach { it.release() }
            return null
        }

        val peri = Imgproc.arcLength(hull2f, true)
        var quad: DocQuad? = null
        for (eps in listOf(0.02, 0.03, 0.05, 0.08, 0.10)) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
            val a = approx.toArray()
            approx.release()
            if (a.size == 4) {
                quad = QuadMath.orderCorners(a.map { it.x.toFloat() to it.y.toFloat() })
                break
            }
        }
        if (quad == null) {
            val rect = Imgproc.minAreaRect(hull2f)
            val box = arrayOfNulls<Point>(4)
            rect.points(box)
            quad = QuadMath.orderCorners(box.filterNotNull().map { it.x.toFloat() to it.y.toFloat() })
        }
        hull2f.release()
        contours.forEach { it.release() }
        return quad
    }

    private fun morph(mask: Mat, op: Int, px: Double) {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(px, px))
        Imgproc.morphologyEx(mask, mask, op, kernel)
        kernel.release()
    }

    private fun kernelPx(base: Int, scale: Double): Double {
        val px = (base * scale).toInt() or 1 // odd
        return max(px, 3).toDouble()
    }

    private const val WORK_EDGE = 1000
    private const val MAX_SAT = 70.0
    private const val MIN_VAL = 110.0
    private const val MIN_BLOB_FRAC = 0.25
}
