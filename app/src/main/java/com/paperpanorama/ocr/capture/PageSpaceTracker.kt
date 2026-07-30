package com.paperpanorama.ocr.capture

import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.NormPoint
import com.paperpanorama.ocr.domain.NormQuad
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Freezes the page rectangle in a seed frame and tracks later frames via affine
 * so an 8×12 grid stays stable while the user zooms into regions.
 */
class PageSpaceTracker {
    private var seedGray: Mat? = null
    private var seedKp: MatOfKeyPoint? = null
    private var seedDesc: Mat? = null
    private var seedW = 0
    private var seedH = 0

    /** Page AABB in seed pixel coords. */
    var pageMinX = 0f
        private set
    var pageMinY = 0f
        private set
    var pageMaxX = 1f
        private set
    var pageMaxY = 1f
        private set

    var isLocked: Boolean = false
        private set

    private var lastAffine: DoubleArray? = null // a00,a01,a02,a10,a11,a12 maps current→seed
    private var lastCellQuads: List<NormQuad> = emptyList()
    private var lastPaperQuad: List<NormPoint> = emptyList()

    fun reset() {
        releaseSeed()
        isLocked = false
        lastAffine = null
        lastCellQuads = emptyList()
        lastPaperQuad = emptyList()
        pageMinX = 0f
        pageMinY = 0f
        pageMaxX = 1f
        pageMaxY = 1f
    }

    /**
     * Lock seed gray + page AABB (seed pixel space).
     * [gray] is cloned/owned by the tracker.
     */
    fun lockSeed(gray: Mat, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        releaseSeed()
        seedGray = gray.clone()
        seedW = gray.cols()
        seedH = gray.rows()
        pageMinX = minX
        pageMinY = minY
        pageMaxX = maxX.coerceAtLeast(minX + 1f)
        pageMaxY = maxY.coerceAtLeast(minY + 1f)
        detectSeedFeats(seedGray!!)
        isLocked = true
        lastAffine = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        lastCellQuads = buildCellQuadsNorm(seedW, seedH, lastAffine!!)
        lastPaperQuad = buildPaperQuadNorm(seedW, seedH, lastAffine!!)
    }

    data class TrackResult(
        val ok: Boolean,
        val cellQuads: List<NormQuad>,
        val paperQuad: List<NormPoint>,
        /** Maps current frame pixels → seed pixels. */
        val affineCurrentToSeed: DoubleArray?,
        val focusedCellIndex: Int,
        val targetAligned: Boolean,
        val targetCellIndex: Int,
    )

    /**
     * Track [grayCurrent] against seed. On failure returns last good overlay quads.
     * Resizes current to seed size for matching, then scales affine to full-res coords
     * so stills (~1200px) register against a live-locked seed (~480px).
     */
    fun track(
        grayCurrent: Mat,
        targetCellIndex: Int,
    ): TrackResult {
        if (!isLocked || seedDesc == null || seedKp == null || seedW <= 0 || seedH <= 0) {
            return TrackResult(false, emptyList(), emptyList(), null, -1, false, targetCellIndex)
        }
        val cw = grayCurrent.cols()
        val ch = grayCurrent.rows()
        val work = if (cw == seedW && ch == seedH) {
            grayCurrent
        } else {
            Mat().also {
                Imgproc.resize(
                    grayCurrent,
                    it,
                    org.opencv.core.Size(seedW.toDouble(), seedH.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            }
        }
        try {
            val affineSmall = matchAffine(work) ?: run {
                return TrackResult(
                    ok = false,
                    cellQuads = lastCellQuads,
                    paperQuad = lastPaperQuad,
                    affineCurrentToSeed = lastAffine,
                    focusedCellIndex = focusedFromQuads(lastCellQuads),
                    targetAligned = false,
                    targetCellIndex = targetCellIndex,
                )
            }
            val sx = seedW.toDouble() / cw
            val sy = seedH.toDouble() / ch
            val affine = doubleArrayOf(
                affineSmall[0] * sx,
                affineSmall[1] * sy,
                affineSmall[2],
                affineSmall[3] * sx,
                affineSmall[4] * sy,
                affineSmall[5],
            )
            lastAffine = affine
            val inv = invertAffine(affine) ?: affine
            val cells = buildCellQuadsFromSeedToCurrent(cw, ch, inv)
            val paper = buildPaperQuadFromSeedToCurrent(cw, ch, inv)
            lastCellQuads = cells
            lastPaperQuad = paper
            val focused = focusedFromQuads(cells)
            val aligned = isCellAligned(cells.getOrNull(targetCellIndex.coerceIn(0, cells.lastIndex)))
            return TrackResult(
                ok = true,
                cellQuads = cells,
                paperQuad = paper,
                affineCurrentToSeed = affine,
                focusedCellIndex = focused,
                targetAligned = aligned,
                targetCellIndex = targetCellIndex,
            )
        } finally {
            if (work !== grayCurrent) work.release()
        }
    }

    /** Map current-frame paper rect into seed page coords using last/current affine. */
    fun mapCurrentRectToSeed(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        affineCurrentToSeed: DoubleArray,
    ): PageCoverageModel.PageRect {
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val a = affineCurrentToSeed
            val sx = (a[0] * x + a[1] * y + a[2]).toFloat()
            val sy = (a[3] * x + a[4] * y + a[5]).toFloat()
            return sx to sy
        }
        val pts = listOf(
            map(left, top),
            map(right, top),
            map(right, bottom),
            map(left, bottom),
        )
        val xs = pts.map { it.first }
        val ys = pts.map { it.second }
        return PageCoverageModel.PageRect(
            minX = xs.minOrNull() ?: left,
            minY = ys.minOrNull() ?: top,
            maxX = xs.maxOrNull() ?: right,
            maxY = ys.maxOrNull() ?: bottom,
        )
    }

    fun pageRectSeed(): PageCoverageModel.PageRect =
        PageCoverageModel.PageRect(pageMinX, pageMinY, pageMaxX, pageMaxY)

    private fun matchAffine(gray: Mat): DoubleArray? {
        val seedD = seedDesc ?: return null
        val seedK = seedKp ?: return null
        val kp = MatOfKeyPoint()
        val desc = Mat()
        detector().detectAndCompute(gray, Mat(), kp, desc)
        if (desc.empty() || kp.empty()) {
            kp.release()
            desc.release()
            return null
        }
        val knn = mutableListOf<MatOfDMatch>()
        try {
            BFMatcher.create(Core.NORM_L2, false).knnMatch(seedD, desc, knn, 2)
        } catch (_: Throwable) {
            knn.clear()
            try {
                BFMatcher.create(Core.NORM_HAMMING, false).knnMatch(seedD, desc, knn, 2)
            } catch (_: Throwable) {
                kp.release()
                desc.release()
                return null
            }
        }
        val good = mutableListOf<DMatch>()
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size >= 2 && arr[0].distance < 0.75f * arr[1].distance) {
                good.add(arr[0])
            }
            m.release()
        }
        if (good.size < 10) {
            kp.release()
            desc.release()
            return null
        }
        val kpSeed = seedK.toArray()
        val kpCur = kp.toArray()
        // Match: query=seed, train=current → we need current→seed, so src=current, dst=seed
        val ptsCur = good.map { Point(kpCur[it.trainIdx].pt.x, kpCur[it.trainIdx].pt.y) }
        val ptsSeed = good.map { Point(kpSeed[it.queryIdx].pt.x, kpSeed[it.queryIdx].pt.y) }
        val src = MatOfPoint2f(*ptsCur.toTypedArray())
        val dst = MatOfPoint2f(*ptsSeed.toTypedArray())
        val inliers = Mat()
        val affine = Calib3d.estimateAffinePartial2D(
            src, dst, inliers, Calib3d.RANSAC, 3.0, 2000, 0.99, 10,
        )
        src.release()
        dst.release()
        val inl = Core.countNonZero(inliers)
        inliers.release()
        kp.release()
        desc.release()
        if (affine.empty() || inl < 8) {
            affine.release()
            return null
        }
        val a00 = affine.get(0, 0)[0]
        val a01 = affine.get(0, 1)[0]
        val a02 = affine.get(0, 2)[0]
        val a10 = affine.get(1, 0)[0]
        val a11 = affine.get(1, 1)[0]
        val a12 = affine.get(1, 2)[0]
        affine.release()
        val scale = hypot(a00, a10)
        if (scale < 0.08 || scale > 8.0) return null
        return doubleArrayOf(a00, a01, a02, a10, a11, a12)
    }

    private fun invertAffine(a: DoubleArray): DoubleArray? {
        val a00 = a[0]; val a01 = a[1]; val a02 = a[2]
        val a10 = a[3]; val a11 = a[4]; val a12 = a[5]
        val det = a00 * a11 - a01 * a10
        if (kotlin.math.abs(det) < 1e-8) return null
        val inv00 = a11 / det
        val inv01 = -a01 / det
        val inv10 = -a10 / det
        val inv11 = a00 / det
        val inv02 = -(inv00 * a02 + inv01 * a12)
        val inv12 = -(inv10 * a02 + inv11 * a12)
        return doubleArrayOf(inv00, inv01, inv02, inv10, inv11, inv12)
    }

    private fun buildCellQuadsNorm(frameW: Int, frameH: Int, currentToSeed: DoubleArray): List<NormQuad> {
        val inv = invertAffine(currentToSeed) ?: return emptyList()
        return buildCellQuadsFromSeedToCurrent(frameW, frameH, inv)
    }

    private fun buildPaperQuadNorm(frameW: Int, frameH: Int, currentToSeed: DoubleArray): List<NormPoint> {
        val inv = invertAffine(currentToSeed) ?: return emptyList()
        return buildPaperQuadFromSeedToCurrent(frameW, frameH, inv)
    }

    private fun buildCellQuadsFromSeedToCurrent(
        frameW: Int,
        frameH: Int,
        seedToCurrent: DoubleArray,
    ): List<NormQuad> {
        val cols = CoverageSnapshot.GRID_COLS
        val rows = CoverageSnapshot.GRID_ROWS
        val spanX = pageMaxX - pageMinX
        val spanY = pageMaxY - pageMinY
        fun seedToNorm(sx: Float, sy: Float): NormPoint {
            val a = seedToCurrent
            val cx = (a[0] * sx + a[1] * sy + a[2]).toFloat()
            val cy = (a[3] * sx + a[4] * sy + a[5]).toFloat()
            return NormPoint(
                (cx / frameW).coerceIn(-0.5f, 1.5f),
                (cy / frameH).coerceIn(-0.5f, 1.5f),
            )
        }
        val out = ArrayList<NormQuad>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = pageMinX + (c.toFloat() / cols) * spanX
                val x1 = pageMinX + ((c + 1).toFloat() / cols) * spanX
                val y0 = pageMinY + (r.toFloat() / rows) * spanY
                val y1 = pageMinY + ((r + 1).toFloat() / rows) * spanY
                out.add(
                    NormQuad(
                        tl = seedToNorm(x0, y0),
                        tr = seedToNorm(x1, y0),
                        br = seedToNorm(x1, y1),
                        bl = seedToNorm(x0, y1),
                    ),
                )
            }
        }
        return out
    }

    private fun buildPaperQuadFromSeedToCurrent(
        frameW: Int,
        frameH: Int,
        seedToCurrent: DoubleArray,
    ): List<NormPoint> {
        fun seedToNorm(sx: Float, sy: Float): NormPoint {
            val a = seedToCurrent
            val cx = (a[0] * sx + a[1] * sy + a[2]).toFloat()
            val cy = (a[3] * sx + a[4] * sy + a[5]).toFloat()
            return NormPoint(cx / frameW, cy / frameH)
        }
        return listOf(
            seedToNorm(pageMinX, pageMinY),
            seedToNorm(pageMaxX, pageMinY),
            seedToNorm(pageMaxX, pageMaxY),
            seedToNorm(pageMinX, pageMaxY),
        )
    }

    private fun focusedFromQuads(cells: List<NormQuad>): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        cells.forEachIndexed { i, q ->
            val c = q.center()
            val d = (c.x - 0.5f) * (c.x - 0.5f) + (c.y - 0.5f) * (c.y - 0.5f)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun isCellAligned(quad: NormQuad?): Boolean {
        if (quad == null) return false
        val c = quad.center()
        val near = kotlin.math.abs(c.x - 0.5f) < 0.28f && kotlin.math.abs(c.y - 0.5f) < 0.28f
        val large = quad.width() > 0.12f && quad.height() > 0.10f
        return near && large
    }

    private fun detectSeedFeats(gray: Mat) {
        seedKp?.release()
        seedDesc?.release()
        val kp = MatOfKeyPoint()
        val desc = Mat()
        detector().detectAndCompute(gray, Mat(), kp, desc)
        seedKp = kp
        seedDesc = desc
    }

    private fun detector() = try {
        SIFT.create(0, 3, 0.04, 10.0, 1.6)
    } catch (_: Throwable) {
        ORB.create(1200)
    }

    private fun releaseSeed() {
        seedGray?.release()
        seedGray = null
        seedKp?.release()
        seedKp = null
        seedDesc?.release()
        seedDesc = null
        seedW = 0
        seedH = 0
    }
}
