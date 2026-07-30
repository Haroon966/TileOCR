package com.paperpanorama.ocr.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.paperpanorama.ocr.doc.MlPaperSegmenter
import com.paperpanorama.ocr.doc.PaperMask
import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import com.paperpanorama.ocr.util.BitmapDecode
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 8×12 page-space coverage + ≤30% locked-overlap duplicate gate.
 */
class CoverageTracker(private val context: Context) {
    private val model = PageCoverageModel()
    val spaceTracker = PageSpaceTracker()

    @Volatile
    private var mosaicThumb: Bitmap? = null

    fun reset() {
        model.reset()
        spaceTracker.reset()
        mosaicThumb?.recycle()
        mosaicThumb = null
    }

    fun snapshot(): CoverageSnapshot = model.snapshot()

    fun mosaicThumbnail(): Bitmap? = mosaicThumb

    fun activeTileIndex(): Int = model.activeCellIndex()

    /** Live Phase A completed — mark mapped; seed lock happens on first still or [lockSeedFromGray]. */
    fun markPageMappedFromLive(minX: Float = 0f, minY: Float = 0f, maxX: Float = 1f, maxY: Float = 1f) {
        if (!model.isPageMapped()) {
            if (maxX > minX && maxY > minY) {
                model.markPageMapped(minX, minY, maxX, maxY)
            } else {
                model.markPageMapped()
            }
        }
    }

    fun ingest(uri: Uri): Result {
        if (!OpenCvBootstrap.ensureInitialized()) {
            model.markNoPaper()
            return Result(accepted = false, snapshot = model.snapshot(), reason = "no_opencv", tileIndex = 0)
        }
        val path = uri.path ?: return rejectBlurry()
        val bgr = loadWork(path) ?: return rejectBlurry()
        try {
            val sharp = Sharpness.laplacianVariance(bgr)
            if (sharp < Sharpness.MIN_ACCEPT) {
                model.markBlurry()
                updateMosaic()
                return Result(accepted = false, snapshot = model.snapshot(), reason = "blurry", tileIndex = model.activeCellIndex())
            }

            val paper = paperBounds(bgr)
            if (paper == null) {
                model.markNoPaper()
                return Result(accepted = false, snapshot = model.snapshot(), reason = "no_paper", tileIndex = model.activeCellIndex())
            }

            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

            // Sync model page rect from live-locked tracker if needed.
            if (spaceTracker.isLocked && !model.isPageMapped()) {
                val pr = spaceTracker.pageRectSeed()
                model.markPageMapped(pr.minX, pr.minY, pr.maxX, pr.maxY)
            }

            if (!spaceTracker.isLocked) {
                val paperRect = PageEdgeClassifier.fromBoundingBox(paper.x, paper.y, paper.width, paper.height)
                val flags = PageEdgeClassifier.classify(paperRect, bgr.cols(), bgr.rows())
                val longVert = PageEdgeClassifier.isLongAxisVertical(paperRect)
                val frac = paper.width.toFloat() * paper.height / (bgr.cols() * bgr.rows())
                if (!model.isPageMapped()) {
                    if (flags.fillsFrame(longVert) && frac > 0.88f) {
                        gray.release()
                        model.markPullBack()
                        return Result(accepted = false, snapshot = model.snapshot(), reason = "pull_back", tileIndex = 0)
                    }
                    if (frac < 0.20f) {
                        gray.release()
                        model.markPullBack()
                        return Result(accepted = false, snapshot = model.snapshot(), reason = "too_small", tileIndex = 0)
                    }
                    model.markPageMapped(
                        paper.x.toFloat(),
                        paper.y.toFloat(),
                        (paper.x + paper.width).toFloat(),
                        (paper.y + paper.height).toFloat(),
                    )
                }
                spaceTracker.lockSeed(
                    gray,
                    model.pageMinX,
                    model.pageMinY,
                    model.pageMaxX,
                    model.pageMaxY,
                )
                val rect = PageCoverageModel.PageRect(
                    paper.x.toFloat(),
                    paper.y.toFloat(),
                    (paper.x + paper.width).toFloat(),
                    (paper.y + paper.height).toFloat(),
                )
                paintAndAccept(bgr, paper, rect, sharp)
                gray.release()
                return Result(accepted = true, snapshot = model.snapshot(), reason = null, tileIndex = model.activeCellIndex())
            }

            val track = spaceTracker.track(gray, model.activeCellIndex())
            gray.release()
            val affine = track.affineCurrentToSeed
            if (!track.ok || affine == null) {
                model.markRegistrationFailed()
                return Result(
                    accepted = false,
                    snapshot = model.snapshot(),
                    reason = "no_match",
                    tileIndex = model.activeCellIndex(),
                )
            }

            val footprint = spaceTracker.mapCurrentRectToSeed(
                paper.x.toFloat(),
                paper.y.toFloat(),
                (paper.x + paper.width).toFloat(),
                (paper.y + paper.height).toFloat(),
                affine,
            )

            if (!model.shouldAccept(footprint, sharp)) {
                model.markDuplicate()
                updateMosaic()
                return Result(
                    accepted = false,
                    snapshot = model.snapshot(),
                    reason = "duplicate",
                    tileIndex = model.activeCellIndex(),
                )
            }

            paintAndAccept(bgr, paper, footprint, sharp)
            return Result(accepted = true, snapshot = model.snapshot(), reason = null, tileIndex = model.activeCellIndex())
        } finally {
            bgr.release()
        }
    }

    fun rebuild(uris: List<Uri>): CoverageSnapshot {
        // Keep seed lock so live overlay stays zoom-stable; only clear cell paint.
        model.reset()
        mosaicThumb?.recycle()
        mosaicThumb = null
        if (spaceTracker.isLocked) {
            val pr = spaceTracker.pageRectSeed()
            model.markPageMapped(pr.minX, pr.minY, pr.maxX, pr.maxY)
        }
        for (uri in uris) {
            ingest(uri)
        }
        return model.snapshot()
    }

    private fun paintAndAccept(
        bgr: Mat,
        paper: Rect,
        footprint: PageCoverageModel.PageRect,
        frameSharp: Float,
    ) {
        model.paintRect(footprint, frameSharp)
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val midX = paper.x + paper.width / 2
        val midY = paper.y + paper.height / 2
        samplePatch(gray, paper.x, paper.y, midX, midY)?.let { s ->
            if (s > frameSharp) model.paintRect(footprint, s)
        }
        gray.release()
        model.onStillAccepted()
        updateMosaic()
    }

    private fun samplePatch(gray: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Float? {
        val w = (x1 - x0).coerceAtLeast(8)
        val h = (y1 - y0).coerceAtLeast(8)
        if (x0 < 0 || y0 < 0 || x0 + w > gray.cols() || y0 + h > gray.rows()) return null
        val roi = Mat(gray, org.opencv.core.Rect(x0, y0, w, h))
        val s = Sharpness.laplacianVariance(roi)
        // roi is a header on gray — do not release gray; roi.release is no-op for submat carefully
        roi.release()
        return s
    }

    private fun rejectBlurry(): Result {
        model.markBlurry()
        return Result(accepted = false, snapshot = model.snapshot(), reason = "blurry", tileIndex = model.activeCellIndex())
    }

    private fun updateMosaic() {
        val snap = model.snapshot()
        if (snap.cells.isEmpty()) return
        val cols = snap.gridCols
        val rows = snap.gridRows
        val cell = 8
        val bmp = mosaicThumb?.takeIf { it.width == cols * cell && it.height == rows * cell }
            ?: Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888).also {
                mosaicThumb?.recycle()
                mosaicThumb = it
            }
        val canvas = Canvas(bmp)
        val paint = Paint()
        snap.cells.forEachIndexed { i, state ->
            val c = i % cols
            val r = i / cols
            paint.color = when (state) {
                BandScanState.Empty -> Color.argb(80, 171, 21, 9)
                BandScanState.Soft -> Color.argb(200, 171, 21, 9)
                BandScanState.Locked -> Color.argb(230, 255, 247, 211)
            }
            canvas.drawRect(
                (c * cell).toFloat(),
                (r * cell).toFloat(),
                ((c + 1) * cell).toFloat(),
                ((r + 1) * cell).toFloat(),
                paint,
            )
        }
    }

    private fun paperBounds(bgr: Mat): Rect? {
        val blob = MlPaperSegmenter.paperBlob(context, bgr)
            ?: runCatching { PaperMask.paperBlob(bgr) }.getOrNull()
            ?: return null
        val rect = Imgproc.boundingRect(blob)
        blob.release()
        if (rect.width < 8 || rect.height < 8) return null
        return rect
    }

    private fun loadWork(path: String): Mat? {
        val full = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (full.empty()) {
            full.release()
            val bmp = BitmapDecode.decodeDownsampled(path, WORK_LONG_EDGE) ?: return null
            val rgba = Mat()
            org.opencv.android.Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            return ensureMax(bgr, WORK_LONG_EDGE)
        }
        return ensureMax(full, WORK_LONG_EDGE)
    }

    private fun ensureMax(src: Mat, longEdge: Int): Mat {
        val long = max(src.cols(), src.rows())
        if (long <= longEdge) return src
        val s = longEdge.toDouble() / long
        val dst = Mat()
        Imgproc.resize(src, dst, org.opencv.core.Size(src.cols() * s, src.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        src.release()
        return dst
    }

    data class Result(
        val accepted: Boolean,
        val snapshot: CoverageSnapshot,
        val reason: String?,
        val tileIndex: Int,
    )

    companion object {
        private const val WORK_LONG_EDGE = 1200
    }
}
