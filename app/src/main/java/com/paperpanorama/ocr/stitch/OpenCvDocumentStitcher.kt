package com.paperpanorama.ocr.stitch

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.paperpanorama.ocr.doc.MlPaperSegmenter
import com.paperpanorama.ocr.doc.PaperMask
import com.paperpanorama.ocr.doc.QuadMath
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.StitchResult
import com.paperpanorama.ocr.orient.FrameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.Feature2D
import org.opencv.features2d.ORB
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Chain-homography document stitcher.
 *
 * Consecutive shots of the same page overlap heavily and match each other far
 * better than they match a blended mosaic, and handheld shots differ by real
 * perspective (not just shift/rotation). So instead of growing a mosaic with
 * affine merges, we: match consecutive tiles with homographies, compose the
 * transforms into the first tile's frame, then warp every tile into one canvas
 * in a single compositing step. Perspective of the composite is removed later
 * by the page quad detect + warp stage.
 */
class OpenCvDocumentStitcher(
    private val context: Context,
    private val normalizer: FrameNormalizer,
    private val matchLongEdge: Int = 1400,
) : DocumentStitcher {

    override suspend fun stitch(
        frames: List<CaptureFrame>,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): StitchResult = withContext(Dispatchers.Default) {
        if (!OpenCvBootstrap.ensureInitialized()) {
            return@withContext StitchResult.Failed("OpenCV failed to load", frames.firstOrNull()?.uri)
        }
        if (frames.isEmpty()) {
            return@withContext StitchResult.Failed("No frames", null)
        }
        if (frames.size == 1) {
            onProgress(0.5f, "Uprighting…")
            val upright = runCatching { normalizer.autoUprightAndDeskew(frames[0].uri) }
                .getOrDefault(frames[0].uri)
            onProgress(1f, "Done")
            return@withContext StitchResult.Ok(upright)
        }

        val entries = arrayOfNulls<TileEntry>(frames.size)
        // Tile-frame transforms into the reference (first placed tile) frame.
        val transforms = arrayOfNulls<Mat>(frames.size)
        fun releaseAllState() {
            entries.forEach { it?.release() }
            entries.fill(null)
            transforms.forEach { it?.release() }
            transforms.fill(null)
        }
        try {
            // 1. Per-shot edge processing + features, once per tile.
            val total = frames.size
            for (i in frames.indices) {
                coroutineContext.ensureActive()
                if (isCancelled()) {
                    releaseAllState()
                    return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
                }
                onProgress(0.05f + 0.45f * i / total, "Processing photo ${i + 1} of $total…")
                val tile = loadMat(frames[i].uri) ?: continue
                val feats = detectFeats(tile)
                if (feats == null) {
                    tile.release()
                    continue
                }
                entries[i] = TileEntry(tile, feats)
            }

            // 2. Chain: register each tile against the most recently placed one,
            // falling back to earlier placed tiles when consecutive shots skip.
            val placed = mutableListOf<Int>()
            for (i in frames.indices) {
                val entry = entries[i] ?: continue
                coroutineContext.ensureActive()
                if (isCancelled()) {
                    releaseAllState()
                    return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
                }
                if (placed.isEmpty()) {
                    transforms[i] = Mat.eye(3, 3, CvType.CV_64F)
                    placed.add(i)
                    continue
                }
                onProgress(0.5f + 0.2f * placed.size / total, "Aligning photo ${i + 1} of $total…")
                for (j in placed.asReversed()) {
                    val h = pairHomography(entries[j]!!, entry) ?: continue
                    val composed = Mat()
                    Core.gemm(transforms[j]!!, h, 1.0, Mat(), 0.0, composed)
                    h.release()
                    transforms[i] = composed
                    placed.add(i)
                    break
                }
                if (transforms[i] == null) {
                    android.util.Log.i(TIMING_TAG, "tile $i dropped: no anchor matched")
                }
            }

            if (placed.size <= 1) {
                releaseAllState()
                return@withContext failWithBest(
                    frames,
                    "Not enough overlap between photos — try again with ~40% overlap",
                )
            }
            val droppedTiles = frames.indices.count { entries[it] != null && transforms[it] == null }

            // 3. Composite all placed tiles into one canvas.
            onProgress(0.72f, "Compositing…")
            val composite = composite(placed.map { entries[it]!!.mat to transforms[it]!! })
            releaseAllState()
            if (composite == null) {
                return@withContext failWithBest(frames, "Stitch produced empty mosaic")
            }

            var resultMat = cropToContent(composite)
            resultMat = ensureMaxLongEdge(resultMat, MAX_MOSAIC_LONG_EDGE).also {
                if (it !== resultMat) resultMat.release()
            }
            onProgress(0.85f, "Saving mosaic…")
            val sessionDir = File(context.cacheDir, "stitch").also { it.mkdirs() }
            val rawFile = File(sessionDir, "mosaic_raw_${System.currentTimeMillis()}.jpg")
            if (!saveMatJpeg(resultMat, rawFile)) {
                resultMat.release()
                return@withContext failWithBest(frames, "Could not save mosaic (out of memory)")
            }
            resultMat.release()

            onProgress(0.92f, "Auto-upright…")
            val upright = runCatching {
                normalizer.autoUprightAndDeskew(Uri.fromFile(rawFile))
            }.getOrDefault(Uri.fromFile(rawFile))
            onProgress(1f, "Done")
            StitchResult.Ok(upright, usedFallback = droppedTiles > 0)
        } catch (e: Throwable) {
            releaseAllState()
            if (isCancelled() || e is kotlinx.coroutines.CancellationException) {
                return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
            }
            failWithBest(frames, e.message ?: "Stitch failed")
        }
    }

    private suspend fun failWithBest(frames: List<CaptureFrame>, reason: String): StitchResult {
        val best = pickSharpest(frames)
        val upright = best?.let {
            runCatching { normalizer.autoUprightAndDeskew(it) }.getOrDefault(it)
        }
        return StitchResult.Failed(reason, upright)
    }

    private class TileEntry(val mat: Mat, val feats: Feats) {
        fun release() {
            mat.release()
            feats.release()
        }
    }

    /** Features of a downscaled image plus the downscale geometry. */
    private class Feats(
        val kp: MatOfKeyPoint,
        val desc: Mat,
        val smallCols: Int,
        val smallRows: Int,
        val isOrb: Boolean,
    ) {
        fun release() {
            kp.release()
            desc.release()
        }
    }

    private fun detectFeats(src: Mat): Feats? {
        val t = android.os.SystemClock.elapsedRealtime()
        val small = downscale(src, matchLongEdge)
        val detector = createDetector()
        val kp = MatOfKeyPoint()
        val desc = Mat()
        detector.detectAndCompute(small, Mat(), kp, desc)
        val feats = Feats(kp, desc, small.cols(), small.rows(), detector is ORB)
        if (small !== src) small.release()
        android.util.Log.i(
            TIMING_TAG,
            "detectFeats ${src.cols()}x${src.rows()} kp=${kp.rows()} " +
                "took=${android.os.SystemClock.elapsedRealtime() - t}ms",
        )
        if (desc.empty() || kp.empty()) {
            feats.release()
            return null
        }
        return feats
    }

    /**
     * Homography mapping [next]'s full-resolution coords into [base]'s.
     * Null when the pair doesn't share enough trustworthy structure.
     */
    private fun pairHomography(base: TileEntry, next: TileEntry): Mat? {
        val t = android.os.SystemClock.elapsedRealtime()
        try {
            return pairHomographyInner(base, next)
        } finally {
            android.util.Log.i(
                TIMING_TAG,
                "pairHomography took=${android.os.SystemClock.elapsedRealtime() - t}ms",
            )
        }
    }

    private fun pairHomographyInner(base: TileEntry, next: TileEntry): Mat? {
        val baseFeats = base.feats
        val nextFeats = next.feats
        val normType = if (baseFeats.isOrb) Core.NORM_HAMMING else Core.NORM_L2
        val matcher = BFMatcher.create(normType, false)
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(baseFeats.desc, nextFeats.desc, knn, 2)
        val good = mutableListOf<DMatch>()
        val ratio = 0.75f
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size >= 2 && arr[0].distance < ratio * arr[1].distance) {
                good.add(arr[0])
            }
            m.release()
        }
        if (good.size < MIN_GOOD_MATCHES) return null

        // Rescale matched points to full-resolution coordinates so the transform
        // is exact regardless of per-tile downscale factors.
        val baseScaleX = base.mat.cols().toDouble() / baseFeats.smallCols
        val baseScaleY = base.mat.rows().toDouble() / baseFeats.smallRows
        val nextScaleX = next.mat.cols().toDouble() / nextFeats.smallCols
        val nextScaleY = next.mat.rows().toDouble() / nextFeats.smallRows
        val kp1Arr = baseFeats.kp.toArray()
        val kp2Arr = nextFeats.kp.toArray()
        val pts1 = good.map { m ->
            val p = kp1Arr[m.queryIdx].pt
            Point(p.x * baseScaleX, p.y * baseScaleY)
        }
        val pts2 = good.map { m ->
            val p = kp2Arr[m.trainIdx].pt
            Point(p.x * nextScaleX, p.y * nextScaleY)
        }
        val srcPts = MatOfPoint2f(*pts2.toTypedArray())
        val dstPts = MatOfPoint2f(*pts1.toTypedArray())
        val inliers = Mat()
        val ransacThreshold = 3.0 * max(max(baseScaleX, nextScaleX), 1.0)
        val h = Calib3d.findHomography(
            srcPts,
            dstPts,
            Calib3d.RANSAC,
            ransacThreshold,
            inliers,
            5000,
            0.995,
        )
        srcPts.release()
        dstPts.release()
        val inlierCount = Core.countNonZero(inliers)
        inliers.release()
        if (h.empty() || h.rows() != 3) {
            h.release()
            return null
        }

        // Demand real consensus: a wrong-but-confident transform pastes tiles at
        // bogus offsets. Fraction floor kept low — textured/crumpled content
        // inflates good-match counts on genuine merges.
        val inlierFraction = inlierCount.toFloat() / good.size
        if (inlierCount < MIN_INLIERS || inlierFraction < MIN_INLIER_FRACTION) {
            h.release()
            return null
        }

        // Normalize so h22 == 1, then sanity-check the linear part: handheld doc
        // shots are near-same scale, not mirrored, and rotated near a multiple of
        // 90° (per-tile auto-upright can disagree by a quarter turn).
        val h22 = h.get(2, 2)[0]
        if (abs(h22) < 1e-9) {
            h.release()
            return null
        }
        Core.divide(h, Scalar(h22), h)
        val h00 = h.get(0, 0)[0]
        val h01 = h.get(0, 1)[0]
        val h10 = h.get(1, 0)[0]
        val h11 = h.get(1, 1)[0]
        val det = h00 * h11 - h01 * h10
        val scaleEst = sqrt(abs(det))
        val rotDeg = Math.toDegrees(atan2(h10, h00))
        val rotMod = ((rotDeg % 90.0) + 90.0) % 90.0
        val distToQuarterTurn = min(rotMod, 90.0 - rotMod)
        android.util.Log.i(
            TIMING_TAG,
            "pair good=${good.size} inl=$inlierCount frac=$inlierFraction scale=$scaleEst rot=$rotDeg",
        )
        if (det <= 0 || scaleEst < 0.5 || scaleEst > 2.0 || distToQuarterTurn > 20.0) {
            h.release()
            return null
        }
        return h
    }

    /** Prefer SIFT (in OpenCV 4.9 Maven AAR); ORB fallback. SCANS Stitcher not packaged in AAR. */
    private fun createDetector(): Feature2D {
        return try {
            SIFT.create(0, 3, 0.04, 10.0, 1.6)
        } catch (_: Throwable) {
            ORB.create(2500)
        }
    }

    /**
     * Warp every tile into a shared canvas using its reference-frame transform.
     * Earlier tiles win in overlaps: fill only where the canvas is still empty,
     * so a slightly-off alignment can't ghost or hard-seam over text.
     */
    private fun composite(tiles: List<Pair<Mat, Mat>>): Mat? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for ((tile, g) in tiles) {
            val corners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(tile.cols().toDouble(), 0.0),
                Point(tile.cols().toDouble(), tile.rows().toDouble()),
                Point(0.0, tile.rows().toDouble()),
            )
            val warped = MatOfPoint2f()
            Core.perspectiveTransform(corners, warped, g)
            for (p in warped.toArray()) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            corners.release()
            warped.release()
        }
        var outW = ceil(maxX - minX).toInt()
        var outH = ceil(maxY - minY).toInt()
        if (outW <= 0 || outH <= 0) return null

        var scale = 1.0
        if (outW > MAX_CANVAS_SIDE || outH > MAX_CANVAS_SIDE ||
            outW.toLong() * outH > MAX_CANVAS_PIXELS
        ) {
            scale = min(
                MAX_CANVAS_SIDE.toDouble() / max(outW, outH),
                sqrt(MAX_CANVAS_PIXELS.toDouble() / (outW.toDouble() * outH)),
            )
            outW = (outW * scale).toInt()
            outH = (outH * scale).toInt()
            if (outW <= 0 || outH <= 0) return null
        }
        val shift = Mat.eye(3, 3, CvType.CV_64F)
        shift.put(0, 0, scale, 0.0, -minX * scale)
        shift.put(1, 0, 0.0, scale, -minY * scale)

        val canvas = Mat.zeros(outH, outW, tiles.first().first.type())
        for ((tile, g) in tiles) {
            val m = Mat()
            Core.gemm(shift, g, 1.0, Mat(), 0.0, m)
            val warped = Mat()
            Imgproc.warpPerspective(
                tile,
                warped,
                m,
                Size(outW.toDouble(), outH.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0),
            )
            m.release()

            val canvasGray = Mat()
            Imgproc.cvtColor(canvas, canvasGray, Imgproc.COLOR_BGR2GRAY)
            val emptyMask = Mat()
            Imgproc.threshold(canvasGray, emptyMask, 2.0, 255.0, Imgproc.THRESH_BINARY_INV)
            canvasGray.release()

            val warpedGray = Mat()
            Imgproc.cvtColor(warped, warpedGray, Imgproc.COLOR_BGR2GRAY)
            val warpedMask = Mat()
            Imgproc.threshold(warpedGray, warpedMask, 2.0, 255.0, Imgproc.THRESH_BINARY)
            warpedGray.release()

            val fillMask = Mat()
            Core.bitwise_and(emptyMask, warpedMask, fillMask)
            emptyMask.release()
            warpedMask.release()

            warped.copyTo(canvas, fillMask)
            fillMask.release()
            warped.release()
        }
        shift.release()
        return canvas
    }

    /** Trim empty (black) borders left by the union canvas. */
    private fun cropToContent(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 2.0, 255.0, Imgproc.THRESH_BINARY)
        gray.release()
        val rect = Imgproc.boundingRect(mask)
        mask.release()
        if (rect.width <= 0 || rect.height <= 0 ||
            (rect.width == src.cols() && rect.height == src.rows())
        ) {
            return src
        }
        val cropped = Mat(src, rect).clone()
        src.release()
        return cropped
    }

    private fun downscale(src: Mat, longEdge: Int): Mat {
        val long = max(src.cols(), src.rows())
        if (long <= longEdge) return src.clone()
        val scale = longEdge.toDouble() / long
        val dst = Mat()
        Imgproc.resize(
            src,
            dst,
            Size(src.cols() * scale, src.rows() * scale),
            0.0,
            0.0,
            Imgproc.INTER_AREA,
        )
        return dst
    }

    /** Returns [src] if already small enough; otherwise a new downscaled Mat. */
    private fun ensureMaxLongEdge(src: Mat, longEdge: Int): Mat {
        val long = max(src.cols(), src.rows())
        if (long <= longEdge) return src
        return downscale(src, longEdge)
    }

    /**
     * Per-shot edge processing, right after capture and before any matching:
     * segment the paper (ML first, classic HSV fallback), and when the whole
     * page is visible in the shot, rectify its perspective immediately. Tiles
     * showing only part of the page are masked to paper-on-black instead:
     * background features can't poison matching, the blend already ignores
     * black pixels, and the final quad detect can't be fooled by tablecloth
     * or fingers. Tiles with no credible paper blob pass through.
     */
    private fun maskToPaper(tile: Mat): Mat {
        var t = android.os.SystemClock.elapsedRealtime()
        val blob = MlPaperSegmenter.paperBlob(context, tile)
            ?: runCatching { PaperMask.paperBlob(tile) }.getOrNull()
            ?: return tile
        val segMs = android.os.SystemClock.elapsedRealtime() - t
        t = android.os.SystemClock.elapsedRealtime()
        val rectified = runCatching { rectifyFullPage(tile, blob) }.getOrNull()
        android.util.Log.i(
            TIMING_TAG,
            "maskToPaper seg=${segMs}ms rectify=${android.os.SystemClock.elapsedRealtime() - t}ms " +
                "rectified=${rectified != null}",
        )
        if (rectified != null) {
            blob.release()
            tile.release()
            return rectified
        }
        val out = Mat.zeros(tile.size(), tile.type())
        tile.copyTo(out, blob)
        blob.release()
        tile.release()
        return out
    }

    /**
     * If all four paper corners sit inside the frame (whole page captured in
     * this shot), warp the perspective out and return just the page. Returns
     * null when the page is cut off by the frame edge — those tiles are only
     * masked, and perspective is removed after stitching instead.
     */
    private fun rectifyFullPage(tile: Mat, blob: Mat): Mat? {
        val quad = PaperMask.quadFromBlob(blob) ?: return null
        val w = tile.cols()
        val h = tile.rows()
        val margin = 0.02f * max(w, h)
        val cutOff = quad.points().any { (x, y) ->
            x < margin || y < margin || x > w - margin || y > h - margin
        }
        if (cutOff) return null
        if (QuadMath.area(quad) < 0.25f * w * h) return null

        // Small outward margin so edge glyphs survive; clamp back into frame.
        val q = QuadMath.clampToImage(QuadMath.expand(quad, 0.02f), w, h)
        val (outW, outH) = QuadMath.edgeLengths(q)
        if (outW < 200 || outH < 200) return null
        val src = MatOfPoint2f(
            Point(q.tlX.toDouble(), q.tlY.toDouble()),
            Point(q.trX.toDouble(), q.trY.toDouble()),
            Point(q.brX.toDouble(), q.brY.toDouble()),
            Point(q.blX.toDouble(), q.blY.toDouble()),
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        src.release()
        dst.release()
        val out = Mat()
        Imgproc.warpPerspective(
            tile,
            out,
            transform,
            Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            Scalar(0.0, 0.0, 0.0),
        )
        transform.release()
        return out
    }

    private fun loadMat(uri: Uri): Mat? {
        val path = uri.path ?: return null
        val full = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (!full.empty()) {
            val work = ensureMaxLongEdge(full, WORK_LONG_EDGE)
            if (work !== full) full.release()
            return maskToPaper(work)
        }
        full.release()
        // Fallback for odd caches still written as Android bitmaps
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, WORK_LONG_EDGE)
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val tmp = Mat()
        org.opencv.android.Utils.bitmapToMat(bmp, tmp)
        bmp.recycle()
        val bgr = Mat()
        Imgproc.cvtColor(tmp, bgr, Imgproc.COLOR_RGBA2BGR)
        tmp.release()
        val work = ensureMaxLongEdge(bgr, WORK_LONG_EDGE).also { if (it !== bgr) bgr.release() }
        return maskToPaper(work)
    }

    private fun saveMatJpeg(mat: Mat, file: File): Boolean {
        file.parentFile?.mkdirs()
        return try {
            Imgcodecs.imwrite(file.absolutePath, mat)
        } catch (_: Throwable) {
            false
        }
    }

    private fun pickSharpest(frames: List<CaptureFrame>): Uri? {
        if (!OpenCvBootstrap.ensureInitialized()) return frames.firstOrNull()?.uri
        var bestUri: Uri? = null
        var bestScore = -1.0
        for (f in frames) {
            val mat = loadMat(f.uri) ?: continue
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val lap = Mat()
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(lap, mean, std)
            val score = std.toArray().firstOrNull() ?: 0.0
            mean.release()
            std.release()
            lap.release()
            gray.release()
            mat.release()
            if (score > bestScore) {
                bestScore = score
                bestUri = f.uri
            }
        }
        return bestUri ?: frames.firstOrNull()?.uri
    }

    companion object {
        /**
         * Tiles are merged at this working resolution. Full-res tiles (12MP+) made
         * every union canvas exceed the pixel budget, so no merge could land.
         * ~2200px long edge ≈ 200dpi for A4 — plenty for OCR.
         */
        private const val WORK_LONG_EDGE = 2200

        /** Keep final mosaic bounded to avoid OOM on mid phones. */
        private const val MAX_MOSAIC_LONG_EDGE = 4200
        private const val MAX_CANVAS_SIDE = 9000
        private const val MAX_CANVAS_PIXELS = 30_000_000L

        /**
         * Pair acceptance — validated on real captures and WhatsApp-compressed
         * photos. Consecutive shots give 50+ homography inliers even on heavily
         * compressed input; below 20 the transform isn't trustworthy.
         */
        private const val MIN_GOOD_MATCHES = 12
        private const val MIN_INLIERS = 20
        private const val MIN_INLIER_FRACTION = 0.10f

        private const val TIMING_TAG = "StitchTiming"

        fun sampleSizeFor(w: Int, h: Int, maxLong: Int): Int {
            var sample = 1
            var long = max(w, h)
            while (long / sample > maxLong) sample *= 2
            return sample
        }
    }
}
