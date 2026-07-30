package com.paperpanorama.ocr.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.paperpanorama.ocr.capture.PageEdgeClassifier
import com.paperpanorama.ocr.capture.PageSpaceTracker
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.LivePageHint
import com.paperpanorama.ocr.domain.NormPoint
import com.paperpanorama.ocr.doc.PaperMask
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Live paper detection + persistent 8×12 grid via [PageSpaceTracker].
 */
class LivePageAnalyzer(
    private val onHint: (LivePageHint) -> Unit,
) : ImageAnalysis.Analyzer {

    private val lastAnalysisMs = AtomicLong(0)
    private val recentIous = ArrayDeque<Float>(IOU_WINDOW)
    private var lastRect: FloatArray? = null
    private val targetCell = AtomicInteger(0)
    private val trackerRef = AtomicReference<PageSpaceTracker?>(null)
    private val alignStreak = AtomicInteger(0)
    private var lastGoodHint: LivePageHint = LivePageHint.Idle

    fun setPageSpaceTracker(tracker: PageSpaceTracker?) {
        trackerRef.set(tracker)
    }

    fun setActiveTile(index: Int) {
        targetCell.set(index.coerceAtLeast(0))
    }

    fun setPageMapped(mapped: Boolean) {
        // Seed lock is owned by tracker; mapped flag is inferred from tracker.isLocked.
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs.get() < MIN_INTERVAL_MS) return
            lastAnalysisMs.set(now)
            if (!OpenCvBootstrap.ensureInitialized()) {
                onHint(LivePageHint.Idle.copy(hint = "Vision engine loading…", featuresOk = false))
                return
            }
            if (image.width < 16 || image.height < 16) return

            val rotation = image.imageInfo.rotationDegrees
            val gray = yPlaneToGrayMat(image) ?: return
            try {
                val oriented = rotateGray(gray, rotation)
                try {
                    val small = downscaleGray(oriented, WORK_EDGE)
                    try {
                        processFrame(small)
                    } finally {
                        if (small !== oriented) small.release()
                    }
                } finally {
                    if (oriented !== gray) oriented.release()
                }
            } finally {
                gray.release()
            }
        } finally {
            image.close()
        }
    }

    private fun processFrame(small: Mat) {
        val tracker = trackerRef.get()
        val features = countOrb(small)

        // Phase B: tracker already locked — track grid, do not re-split AABB.
        if (tracker != null && tracker.isLocked) {
            val track = tracker.track(small, targetCell.get())
            val stable = pushIouFromPaper(track.paperQuad)
            if (track.targetAligned && track.ok && stable) {
                alignStreak.incrementAndGet()
            } else {
                alignStreak.set(0)
            }
            val aligned = alignStreak.get() >= ALIGN_STREAK
            val featuresOk = features >= LivePageHint.MIN_FEATURES
            val hint = when {
                track.ok.not() -> "Hold steady — keep textured page in view"
                !featuresOk -> "Include more text / texture"
                !stable -> "Hold steady…"
                aligned -> "Hold still — capturing sharp tile"
                else -> "Move closer to the red / missing region"
            }
            val out = LivePageHint(
                paperQuad = track.paperQuad,
                tileQuads = track.cellQuads,
                gridCols = CoverageSnapshot.GRID_COLS,
                gridRows = CoverageSnapshot.GRID_ROWS,
                pullBack = false,
                iouStable = stable,
                featureCount = features,
                featuresOk = featuresOk,
                hint = hint,
                focusedTileIndex = track.focusedCellIndex,
                activeTileAligned = aligned && track.ok,
                pageMapped = true,
                trackingLost = !track.ok,
            )
            if (track.ok) lastGoodHint = out
            onHint(if (track.ok) out else lastGoodHint.copy(trackingLost = true, hint = hint, featureCount = features))
            return
        }

        // Phase A: find whole page and lock seed.
        val bgr = Mat()
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_GRAY2BGR)
        val blob = try {
            PaperMask.paperBlob(bgr)
        } catch (_: Throwable) {
            null
        }
        bgr.release()

        if (blob == null) {
            recentIous.clear()
            lastRect = null
            alignStreak.set(0)
            onHint(
                LivePageHint(
                    featureCount = features,
                    featuresOk = false,
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
        val paperFrac = paper.area / (sw * sh)
        val wholePageOk = flags.longAxisEndsInterior(longVert) &&
            !flags.fillsFrame(longVert) &&
            paperFrac in 0.28f..0.90f

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

        val pullBack = flags.fillsFrame(longVert) || paperFrac > 0.92f || paperFrac < 0.22f

        if (wholePageOk && stable && tracker != null && !tracker.isLocked) {
            tracker.lockSeed(
                small,
                rect.x.toFloat(),
                rect.y.toFloat(),
                (rect.x + rect.width).toFloat(),
                (rect.y + rect.height).toFloat(),
            )
        }

        val featuresOk = features >= LivePageHint.MIN_FEATURES
        val hint = when {
            pullBack -> "Pull back — show the whole page with margins"
            !wholePageOk -> "Frame the whole page so the 8×12 grid can lock on"
            !stable -> "Hold steady to lock the page grid…"
            tracker?.isLocked == true -> "Page mapped — move closer to a red cell"
            else -> "Hold steady to lock the page grid…"
        }

        val mapped = tracker?.isLocked == true
        onHint(
            LivePageHint(
                paperQuad = paperQuad,
                tileQuads = if (mapped) tracker!!.track(small, 0).cellQuads else emptyList(),
                gridCols = CoverageSnapshot.GRID_COLS,
                gridRows = CoverageSnapshot.GRID_ROWS,
                cutOffTop = !flags.topInterior,
                cutOffBottom = !flags.bottomInterior,
                cutOffLeft = !flags.leftInterior,
                cutOffRight = !flags.rightInterior,
                pullBack = pullBack,
                iouStable = stable,
                featureCount = features,
                featuresOk = featuresOk,
                hint = hint,
                focusedTileIndex = -1,
                activeTileAligned = false,
                pageMapped = mapped,
                trackingLost = false,
            ),
        )
    }

    private fun pushIouFromPaper(paperQuad: List<NormPoint>): Boolean {
        if (paperQuad.size < 4) return false
        val xs = paperQuad.map { it.x }
        val ys = paperQuad.map { it.y }
        val nl = xs.minOrNull() ?: return false
        val nr = xs.maxOrNull() ?: return false
        val nt = ys.minOrNull() ?: return false
        val nb = ys.maxOrNull() ?: return false
        val cur = floatArrayOf(nl, nt, nr, nb)
        val iou = lastRect?.let { iouNorm(it, cur) } ?: 0f
        lastRect = cur
        if (recentIous.size >= IOU_WINDOW) recentIous.removeFirst()
        recentIous.addLast(iou)
        return recentIous.size >= IOU_WINDOW && recentIous.all { it >= IOU_STABLE }
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

    private fun rotateGray(src: Mat, degrees: Int): Mat {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val dst = Mat()
        when (d) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return dst
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
        private const val ALIGN_STREAK = 2
    }
}
