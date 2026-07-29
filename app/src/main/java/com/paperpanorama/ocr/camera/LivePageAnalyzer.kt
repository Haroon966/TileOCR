package com.paperpanorama.ocr.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.paperpanorama.ocr.capture.PageEdgeClassifier
import com.paperpanorama.ocr.domain.LivePageHint
import com.paperpanorama.ocr.domain.NormPoint
import com.paperpanorama.ocr.domain.NormQuad
import com.paperpanorama.ocr.doc.PaperMask
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Live paper detection + 2×2 AR tile quads + focus/alignment for the active tile.
 */
class LivePageAnalyzer(
    private val onHint: (LivePageHint) -> Unit,
) : ImageAnalysis.Analyzer {

    private val lastAnalysisMs = AtomicLong(0)
    private val recentIous = ArrayDeque<Float>(IOU_WINDOW)
    private var lastRect: FloatArray? = null
    private val activeTile = AtomicInteger(0)

    fun setActiveTile(index: Int) {
        activeTile.set(index.coerceIn(0, 3))
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs.get() < MIN_INTERVAL_MS) return
            lastAnalysisMs.set(now)
            if (!OpenCvBootstrap.ensureInitialized()) {
                onHint(LivePageHint.Idle)
                return
            }
            val w = image.width
            val h = image.height
            if (w < 16 || h < 16) return

            val gray = yPlaneToGrayMat(image) ?: return
            try {
                val small = downscaleGray(gray, WORK_EDGE)
                try {
                    val bgr = Mat()
                    Imgproc.cvtColor(small, bgr, Imgproc.COLOR_GRAY2BGR)
                    val blob = try {
                        PaperMask.paperBlob(bgr)
                    } catch (_: Throwable) {
                        null
                    }
                    bgr.release()
                    val features = countOrb(small)

                    if (blob == null) {
                        recentIous.clear()
                        lastRect = null
                        onHint(
                            LivePageHint(
                                featureCount = features,
                                featuresOk = features >= LivePageHint.MIN_FEATURES,
                                hint = "Can't see paper — change background or lighting",
                            ),
                        )
                        return
                    }

                    val rect = Imgproc.boundingRect(blob)
                    blob.release()
                    val sw = small.cols().toFloat()
                    val sh = small.rows().toFloat()
                    val paper = PageEdgeClassifier.fromBoundingBox(rect.x, rect.y, rect.width, rect.height)
                    val flags = PageEdgeClassifier.classify(paper, small.cols(), small.rows())
                    val longVert = PageEdgeClassifier.isLongAxisVertical(paper)
                    val pullBack = paper.area > 0.92f * sw * sh

                    val nl = rect.x / sw
                    val nt = rect.y / sh
                    val nr = (rect.x + rect.width) / sw
                    val nb = (rect.y + rect.height) / sh
                    val iou = lastRect?.let { iouNorm(it, floatArrayOf(nl, nt, nr, nb)) } ?: 0f
                    lastRect = floatArrayOf(nl, nt, nr, nb)
                    if (recentIous.size >= IOU_WINDOW) recentIous.removeFirst()
                    recentIous.addLast(iou)
                    val stable = recentIous.size >= IOU_WINDOW && recentIous.all { it >= IOU_STABLE }

                    val paperQuad = listOf(
                        NormPoint(nl, nt),
                        NormPoint(nr, nt),
                        NormPoint(nr, nb),
                        NormPoint(nl, nb),
                    )
                    val tileQuads = splitPaperToTiles(nl, nt, nr, nb)
                    val focused = focusedTile(tileQuads)
                    val want = activeTile.get()
                    val aligned = isAligned(tileQuads.getOrNull(want), want)

                    val featuresOk = features >= LivePageHint.MIN_FEATURES
                    val hint = when {
                        pullBack && goodLookingWhole(flags, longVert) ->
                            null
                        pullBack ->
                            "Pull back — show the whole page with 4 tiles"
                        !flags.topInterior && longVert ->
                            "Top cut off — show full page edges"
                        !flags.bottomInterior && longVert ->
                            "Bottom cut off — show full page edges"
                        !featuresOk ->
                            "Include more text / texture"
                        !stable ->
                            "Hold steady…"
                        want != focused && focused >= 0 ->
                            "Move to the highlighted tile"
                        aligned ->
                            "Hold still — capturing tile ${want + 1}"
                        else ->
                            "Move closer to the highlighted tile"
                    }

                    onHint(
                        LivePageHint(
                            paperQuad = paperQuad,
                            tileQuads = tileQuads,
                            cutOffTop = !flags.topInterior,
                            cutOffBottom = !flags.bottomInterior,
                            cutOffLeft = !flags.leftInterior,
                            cutOffRight = !flags.rightInterior,
                            pullBack = pullBack && !aligned,
                            iouStable = stable,
                            featureCount = features,
                            featuresOk = featuresOk,
                            hint = hint,
                            focusedTileIndex = focused,
                            activeTileAligned = aligned && stable,
                        ),
                    )
                } finally {
                    if (small !== gray) small.release()
                }
            } finally {
                gray.release()
            }
        } finally {
            image.close()
        }
    }

    private fun goodLookingWhole(
        flags: PageEdgeClassifier.EdgeFlags,
        longVert: Boolean,
    ): Boolean = flags.longAxisEndsInterior(longVert)

    private fun splitPaperToTiles(l: Float, t: Float, r: Float, b: Float): List<NormQuad> {
        val mx = (l + r) / 2f
        val my = (t + b) / 2f
        // TL, TR, BL, BR
        return listOf(
            NormQuad(NormPoint(l, t), NormPoint(mx, t), NormPoint(mx, my), NormPoint(l, my)),
            NormQuad(NormPoint(mx, t), NormPoint(r, t), NormPoint(r, my), NormPoint(mx, my)),
            NormQuad(NormPoint(l, my), NormPoint(mx, my), NormPoint(mx, b), NormPoint(l, b)),
            NormQuad(NormPoint(mx, my), NormPoint(r, my), NormPoint(r, b), NormPoint(mx, b)),
        )
    }

    private fun focusedTile(tiles: List<NormQuad>): Int {
        // Which tile center is closest to viewfinder center (0.5, 0.5).
        var best = -1
        var bestDist = Float.MAX_VALUE
        tiles.forEachIndexed { i, q ->
            val c = q.center()
            val d = (c.x - 0.5f) * (c.x - 0.5f) + (c.y - 0.5f) * (c.y - 0.5f)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun isAligned(quad: NormQuad?, index: Int): Boolean {
        if (quad == null) return false
        val c = quad.center()
        // Desired center offsets so each quadrant is brought toward frame center when zoomed in.
        val targetX = when (index % 2) {
            0 -> 0.42f
            else -> 0.58f
        }
        val targetY = when (index / 2) {
            0 -> 0.42f
            else -> 0.58f
        }
        val nearCenter = kotlin.math.abs(c.x - 0.5f) < 0.22f && kotlin.math.abs(c.y - 0.5f) < 0.22f
        // Tile should be reasonably large in the frame (user moved closer).
        val w = kotlin.math.abs(quad.tr.x - quad.tl.x)
        val h = kotlin.math.abs(quad.bl.y - quad.tl.y)
        val largeEnough = w > 0.28f && h > 0.22f
        val toward = kotlin.math.abs(c.x - targetX) < 0.28f && kotlin.math.abs(c.y - targetY) < 0.28f
        return largeEnough && (nearCenter || toward)
    }

    private fun countOrb(gray: Mat): Int {
        val kp = MatOfKeyPoint()
        return try {
            ORB.create(200).detect(gray, kp)
            kp.toArray().size
        } catch (_: Throwable) {
            0
        } finally {
            kp.release()
        }
    }

    private fun downscaleGray(src: Mat, longEdge: Int): Mat {
        val long = max(src.cols(), src.rows())
        if (long <= longEdge) return src
        val s = longEdge.toDouble() / long
        val dst = Mat()
        Imgproc.resize(src, dst, org.opencv.core.Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        return dst
    }

    private fun yPlaneToGrayMat(image: ImageProxy): Mat? {
        val plane = image.planes.getOrNull(0) ?: return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val buf = plane.buffer
        val mat = Mat(h, w, CvType.CV_8UC1)
        if (pixelStride == 1 && rowStride == w) {
            val bytes = ByteArray(w * h)
            buf.get(bytes)
            mat.put(0, 0, bytes)
            return mat
        }
        val row = ByteArray(rowStride)
        val out = ByteArray(w)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(row, 0, min(rowStride, buf.remaining()))
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, 0, w)
            } else {
                var i = 0
                var x = 0
                while (x < w) {
                    out[x] = row[i]
                    i += pixelStride
                    x++
                }
            }
            mat.put(y, 0, out)
        }
        return mat
    }

    private fun iouNorm(a: FloatArray, b: FloatArray): Float {
        val il = max(a[0], b[0])
        val it = max(a[1], b[1])
        val ir = min(a[2], b[2])
        val ib = min(a[3], b[3])
        val iw = (ir - il).coerceAtLeast(0f)
        val ih = (ib - it).coerceAtLeast(0f)
        val inter = iw * ih
        val areaA = (a[2] - a[0]).coerceAtLeast(0f) * (a[3] - a[1]).coerceAtLeast(0f)
        val areaB = (b[2] - b[0]).coerceAtLeast(0f) * (b[3] - b[1]).coerceAtLeast(0f)
        val union = areaA + areaB - inter
        return if (union <= 1e-6f) 0f else inter / union
    }

    companion object {
        private const val MIN_INTERVAL_MS = 200L
        private const val WORK_EDGE = 480
        private const val IOU_WINDOW = 3
        private const val IOU_STABLE = 0.85f
    }
}
