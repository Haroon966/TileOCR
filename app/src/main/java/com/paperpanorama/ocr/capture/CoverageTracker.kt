package com.paperpanorama.ocr.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.paperpanorama.ocr.doc.MlPaperSegmenter
import com.paperpanorama.ocr.doc.PaperMask
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.QuadTileState
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import com.paperpanorama.ocr.util.BitmapDecode
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * 4-tile (2×2) guided capture. Each sharp still is assigned to the active tile.
 * Blurry stills mark that tile for recapture. Does not decide "finish" — UI does.
 */
class CoverageTracker(private val context: Context) {
    private val model = PageCoverageModel()

    @Volatile
    private var mosaicThumb: Bitmap? = null

    fun reset() {
        model.reset()
        mosaicThumb?.recycle()
        mosaicThumb = null
    }

    fun snapshot(): CoverageSnapshot = model.snapshot()

    fun mosaicThumbnail(): Bitmap? = mosaicThumb

    fun activeTileIndex(): Int = model.activeTileIndex()

    /**
     * Ingest a still for the current active tile (or [forceTileIndex] on recapture).
     */
    fun ingest(uri: Uri, forceTileIndex: Int? = null): Result {
        if (!OpenCvBootstrap.ensureInitialized()) {
            model.acceptTile(forceTileIndex ?: model.activeTileIndex())
            return Result(accepted = true, snapshot = model.snapshot(), reason = null, tileIndex = model.activeTileIndex())
        }
        val path = uri.path ?: return rejectBlurry()
        val bgr = loadWork(path) ?: return rejectBlurry()
        try {
            val sharp = Sharpness.laplacianVariance(bgr)
            val tile = (forceTileIndex ?: model.activeTileIndex()).coerceIn(0, 3)

            if (sharp < Sharpness.MIN_ACCEPT) {
                model.rejectBlurry(tile)
                return Result(
                    accepted = false,
                    snapshot = model.snapshot(),
                    reason = "blurry",
                    tileIndex = tile,
                )
            }

            val paper = paperBounds(bgr)
            if (paper == null) {
                model.markNoPaper()
                return Result(accepted = false, snapshot = model.snapshot(), reason = "no_paper", tileIndex = tile)
            }

            val rect = PageEdgeClassifier.fromBoundingBox(paper.x, paper.y, paper.width, paper.height)
            val flags = PageEdgeClassifier.classify(rect, bgr.cols(), bgr.rows())
            // First tile: prefer whole page visible once so the 2×2 map is trustworthy.
            if (model.goodCount() == 0 && model.tileState(0) == QuadTileState.Pending) {
                if (flags.fillsFrame(PageEdgeClassifier.isLongAxisVertical(rect))) {
                    // OK when zoomed into a quadrant for capture — only warn if tiny paper.
                }
                if (!PageEdgeClassifier.isCredibleSeed(rect, bgr.cols(), bgr.rows()) &&
                    paper.area() < bgr.cols() * bgr.rows() * 0.12
                ) {
                    model.markPullBack()
                    return Result(accepted = false, snapshot = model.snapshot(), reason = "pull_back", tileIndex = tile)
                }
            }

            model.acceptTile(tile)
            updateMosaic()
            return Result(
                accepted = true,
                snapshot = model.snapshot(),
                reason = null,
                tileIndex = tile,
            )
        } finally {
            bgr.release()
        }
    }

    fun rebuildFromTileStates(goodIndices: Set<Int>): CoverageSnapshot {
        model.reset()
        for (i in goodIndices.sorted()) {
            model.acceptTile(i)
        }
        updateMosaic()
        return model.snapshot()
    }

    private fun rejectBlurry(): Result {
        val tile = model.activeTileIndex()
        model.rejectBlurry(tile)
        return Result(accepted = false, snapshot = model.snapshot(), reason = "blurry", tileIndex = tile)
    }

    private fun updateMosaic() {
        val snap = model.snapshot()
        val cell = 48
        val bmp = mosaicThumb?.takeIf { it.width == 2 * cell && it.height == 2 * cell }
            ?: Bitmap.createBitmap(2 * cell, 2 * cell, Bitmap.Config.ARGB_8888).also {
                mosaicThumb?.recycle()
                mosaicThumb = it
            }
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        snap.tileStates.forEachIndexed { i, state ->
            val c = i % 2
            val r = i / 2
            paint.color = when (state) {
                QuadTileState.Pending -> Color.argb(60, 255, 247, 211)
                QuadTileState.Blurry -> Color.argb(220, 171, 21, 9)
                QuadTileState.Good -> Color.argb(230, 255, 247, 211)
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

    private fun Rect.area(): Int = width * height

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
