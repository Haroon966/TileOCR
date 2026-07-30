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

    /**
     * Combine already-cropped document pages (ML Kit Document Scanner output).
     * Does **not** geometric-panorama stitch — those pages are independent sheets
     * (or non-overlapping regions), so we stack them into one OCR-ready image.
     */
    suspend fun combineDocumentPages(
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

        val mats = ArrayList<Mat>(frames.size)
        try {
            val total = frames.size
            for ((i, frame) in frames.withIndex()) {
                coroutineContext.ensureActive()
                if (isCancelled()) {
                    mats.forEach { it.release() }
                    return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
                }
                onProgress(0.1f + 0.5f * i / total, "Cropping page ${i + 1} of $total…")
                // Crop paper per page before stacking (lighting/perspective cleaned).
                val mat = loadMat(frame.uri) ?: loadMatRaw(frame.uri) ?: continue
                mats.add(mat)
            }
            if (mats.isEmpty()) {
                return@withContext failWithBest(frames, "Could not read scanned pages")
            }
            onProgress(0.7f, "Combining ${mats.size} pages…")
            val stacked = stackPages(mats) ?: run {
                mats.forEach { it.release() }
                return@withContext failWithBest(frames, "Could not combine pages")
            }
            mats.forEach { it.release() }
            // Already document-cropped — skip deskew that warps tall stacks.
            onProgress(0.9f, "Saving…")
            val sessionDir = File(context.cacheDir, "stitch").also { it.mkdirs() }
            val outFile = File(sessionDir, "pages_stack_${System.currentTimeMillis()}.jpg")
            if (!saveMatJpeg(stacked, outFile)) {
                stacked.release()
                return@withContext failWithBest(frames, "Could not save combined pages")
            }
            stacked.release()
            onProgress(1f, "Done")
            StitchResult.Ok(Uri.fromFile(outFile), usedFallback = true)
        } catch (e: Throwable) {
            mats.forEach { it.release() }
            if (isCancelled() || e is kotlinx.coroutines.CancellationException) {
                return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
            }
            failWithBest(frames, e.message ?: "Combine failed")
        }
    }

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
            // 1. Per-shot paper crop + features (always crop — never skip for “same page”).
            val total = frames.size
            for (i in frames.indices) {
                coroutineContext.ensureActive()
                if (isCancelled()) {
                    releaseAllState()
                    return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
                }
                onProgress(0.05f + 0.45f * i / total, "Cropping photo ${i + 1} of $total…")
                val tile = loadMat(frames[i].uri) ?: loadMatRaw(frames[i].uri) ?: continue
                val feats = detectFeats(tile)
                if (feats == null) {
                    tile.release()
                    continue
                }
                entries[i] = TileEntry(tile, feats)
            }

            // 2. Grow connected components (overlap clusters), then stitch each → stack.
            onProgress(0.5f, "Aligning photos…")
            val components = growComponents(entries, transforms, frames.size) {
                coroutineContext.ensureActive()
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("Cancelled")
            }
            val samePage = looksLikeSamePageTiles(frames)
            val mosaics = ArrayList<Mat>()
            var droppedTiles = 0
            try {
                for ((ci, group) in components.withIndex()) {
                    if (group.isEmpty()) continue
                    onProgress(0.55f + 0.15f * ci / components.size.coerceAtLeast(1), "Compositing group ${ci + 1}…")
                    if (group.size == 1) {
                        if (samePage && components.size > 1) {
                            // Overlapping close-ups that failed match — skip duplicate singles.
                            droppedTiles++
                            continue
                        }
                        mosaics.add(entries[group[0]]!!.mat.clone())
                        continue
                    }
                    val refMat = entries[group.first()]!!.mat
                    var piece = composite(group.map { entries[it]!!.mat to transforms[it]!! })
                    if (piece != null && !isSensibleMosaic(piece, refMat)) {
                        android.util.Log.i(TIMING_TAG, "component $ci mosaic failed sanity")
                        piece.release()
                        piece = null
                    }
                    if (piece != null) {
                        mosaics.add(piece)
                    } else {
                        droppedTiles += group.size - 1
                        mosaics.add(refMat.clone())
                    }
                }

                // Unmatched tiles (no features / no component) — stack only if not same-page dupes.
                val inComponent = components.flatten().toSet()
                if (!samePage) {
                    for (i in frames.indices) {
                        if (entries[i] != null && i !in inComponent) {
                            mosaics.add(entries[i]!!.mat.clone())
                            droppedTiles++
                        }
                    }
                } else {
                    droppedTiles += frames.indices.count { entries[it] != null && it !in inComponent }
                }

                if (mosaics.isEmpty()) {
                    releaseAllState()
                    if (samePage) {
                        onProgress(0.9f, "Could not align tiles — using sharpest shot…")
                        val best = pickSharpest(frames)
                            ?: return@withContext StitchResult.Failed("No frames", null)
                        val upright = runCatching { normalizer.autoUprightAndDeskew(best) }
                            .getOrDefault(best)
                        onProgress(1f, "Done")
                        return@withContext StitchResult.Ok(upright, usedFallback = true)
                    }
                    onProgress(0.72f, "Combining cropped pages…")
                    val mats = frames.mapNotNull { loadMat(it.uri) ?: loadMatRaw(it.uri) }
                    val stacked = stackPages(mats)
                    mats.forEach { it.release() }
                    if (stacked == null) {
                        return@withContext failWithBest(
                            frames,
                            "Not enough overlap between photos — try again with ~40% overlap",
                        )
                    }
                    return@withContext finishMosaic(
                        stacked, frames, droppedTiles = frames.size - 1, onProgress,
                    )
                }

                onProgress(0.72f, "Building final page…")
                val combined = if (mosaics.size == 1) {
                    mosaics[0]
                } else {
                    val stacked = stackPages(mosaics)
                    mosaics.forEach { it.release() }
                    if (stacked == null) {
                        releaseAllState()
                        return@withContext failWithBest(frames, "Stitch produced empty mosaic")
                    }
                    stacked
                }
                releaseAllState()
                finishMosaic(combined, frames, droppedTiles, onProgress)
            } catch (e: Throwable) {
                mosaics.forEach { it.release() }
                throw e
            }
        } catch (e: Throwable) {
            releaseAllState()
            if (isCancelled() || e is kotlinx.coroutines.CancellationException) {
                return@withContext StitchResult.Failed("Cancelled", frames.firstOrNull()?.uri)
            }
            failWithBest(frames, e.message ?: "Stitch failed")
        }
    }

    private suspend fun finishMosaic(
        resultMatIn: Mat,
        frames: List<CaptureFrame>,
        droppedTiles: Int,
        onProgress: (Float, String) -> Unit,
    ): StitchResult {
        var resultMat = cropToContent(resultMatIn)
        resultMat = ensureMaxLongEdge(resultMat, MAX_MOSAIC_LONG_EDGE).also {
            if (it !== resultMat) resultMat.release()
        }
        onProgress(0.85f, "Global polish — exposure / upright…")
        val sessionDir = File(context.cacheDir, "stitch").also { it.mkdirs() }
        val rawFile = File(sessionDir, "mosaic_raw_${System.currentTimeMillis()}.jpg")
        if (!saveMatJpeg(resultMat, rawFile)) {
            resultMat.release()
            return failWithBest(frames, "Could not save mosaic (out of memory)")
        }
        resultMat.release()

        onProgress(0.92f, "Auto-upright polish…")
        val upright = runCatching {
            normalizer.autoUprightAndDeskew(Uri.fromFile(rawFile))
        }.getOrDefault(Uri.fromFile(rawFile))
        onProgress(1f, "Done")
        return StitchResult.Ok(upright, usedFallback = droppedTiles > 0)
    }

    /**
     * Grow overlap clusters: first seed chain in capture order, then new components
     * from remaining unmatched tiles (handles non-overlapping regions / multi-sheet).
     */
    private fun growComponents(
        entries: Array<TileEntry?>,
        transforms: Array<Mat?>,
        frameCount: Int,
        checkCancel: () -> Unit,
    ): List<List<Int>> {
        val components = ArrayList<List<Int>>()
        val claimed = BooleanArray(frameCount)

        fun growFromSeed(seed: Int): List<Int> {
            transforms[seed]?.release()
            transforms[seed] = Mat.eye(3, 3, CvType.CV_64F)
            claimed[seed] = true
            val group = mutableListOf(seed)
            var progressed = true
            while (progressed) {
                progressed = false
                checkCancel()
                for (i in 0 until frameCount) {
                    if (claimed[i] || entries[i] == null) continue
                    val match = bestAnchorMatch(entries[i]!!, group, entries, transforms) ?: continue
                    transforms[i]?.release()
                    transforms[i] = match
                    claimed[i] = true
                    group.add(i)
                    progressed = true
                    android.util.Log.i(TIMING_TAG, "tile $i joined component seed=$seed")
                }
            }
            return group
        }

        // Primary component: walk capture order (same as old chain).
        val first = (0 until frameCount).firstOrNull { entries[it] != null }
        if (first != null) {
            components.add(growFromSeed(first))
            // Orphan retry into primary (order-independent overlaps).
            var progressed = true
            while (progressed) {
                progressed = false
                checkCancel()
                val primary = components[0].toMutableList()
                for (i in 0 until frameCount) {
                    if (claimed[i] || entries[i] == null) continue
                    val match = bestAnchorMatch(entries[i]!!, primary, entries, transforms) ?: continue
                    transforms[i]?.release()
                    transforms[i] = match
                    claimed[i] = true
                    primary.add(i)
                    progressed = true
                    android.util.Log.i(TIMING_TAG, "tile $i recovered into primary")
                }
                components[0] = primary
            }
        }

        // Extra components for remaining unmatched tiles.
        while (true) {
            val seed = (0 until frameCount).firstOrNull { !claimed[it] && entries[it] != null } ?: break
            components.add(growFromSeed(seed))
        }
        return components
    }

    /**
     * Pick the anchor with the strongest transform (most inliers), not merely the
     * first neighbor that passes — ML Kit page order is capture order, not spatial.
     */
    private fun bestAnchorMatch(
        entry: TileEntry,
        placed: List<Int>,
        entries: Array<TileEntry?>,
        transforms: Array<Mat?>,
    ): Mat? {
        val recentFirst = placed.asReversed().take(NEIGHBOR_WINDOW)
        val older = placed.asReversed().drop(NEIGHBOR_WINDOW)
        var bestH: Mat? = null
        var bestScore = -1
        for (j in recentFirst + older) {
            val scored = pairHomographyScored(entries[j]!!, entry) ?: continue
            if (scored.inliers > bestScore) {
                bestH?.release()
                bestScore = scored.inliers
                val composed = Mat()
                Core.gemm(transforms[j]!!, scored.h, 1.0, Mat(), 0.0, composed)
                scored.h.release()
                bestH = composed
            } else {
                scored.h.release()
            }
        }
        return bestH
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
        val smallColor = downscale(src, matchLongEdge)
        val gray = Mat()
        if (smallColor.channels() > 1) {
            Imgproc.cvtColor(smallColor, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            smallColor.copyTo(gray)
        }
        val smallCols = gray.cols()
        val smallRows = gray.rows()
        if (smallColor !== src) smallColor.release()
        // CLAHE — ML Kit cleaned pages are flat white; boost ink for SIFT/ORB.
        val enhanced = Mat()
        Imgproc.createCLAHE(3.0, Size(8.0, 8.0)).apply(gray, enhanced)
        gray.release()
        val detector = createDetector()
        val kp = MatOfKeyPoint()
        val desc = Mat()
        detector.detectAndCompute(enhanced, Mat(), kp, desc)
        enhanced.release()
        val feats = Feats(kp, desc, smallCols, smallRows, detector is ORB)
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

    private data class ScoredH(val h: Mat, val inliers: Int)

    /**
     * Homography (or affine→H) mapping [next]'s full-resolution coords into [base]'s.
     */
    private fun pairHomographyScored(base: TileEntry, next: TileEntry): ScoredH? {
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

    private fun pairHomographyInner(base: TileEntry, next: TileEntry): ScoredH? {
        val baseFeats = base.feats
        val nextFeats = next.feats
        val normType = if (baseFeats.isOrb) Core.NORM_HAMMING else Core.NORM_L2
        val matcher = BFMatcher.create(normType, false)
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(baseFeats.desc, nextFeats.desc, knn, 2)
        val good = mutableListOf<DMatch>()
        val ratio = 0.80f
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size >= 2 && arr[0].distance < ratio * arr[1].distance) {
                good.add(arr[0])
            } else if (arr.size == 1) {
                good.add(arr[0])
            }
            m.release()
        }
        if (good.size < MIN_GOOD_MATCHES) return null

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

        var h: Mat? = null
        var inlierCount = 0

        // Prefer homography; fall back to partial affine (ML Kit pages are already flat).
        run {
            val inliers = Mat()
            val ransacThreshold = 4.0 * max(max(baseScaleX, nextScaleX), 1.0)
            val hh = Calib3d.findHomography(
                srcPts,
                dstPts,
                Calib3d.RANSAC,
                ransacThreshold,
                inliers,
                5000,
                0.995,
            )
            val inl = Core.countNonZero(inliers)
            inliers.release()
            if (!hh.empty() && hh.rows() == 3 && inl >= MIN_INLIERS) {
                h = hh
                inlierCount = inl
            } else {
                hh.release()
            }
        }
        if (h == null) {
            val inliers = Mat()
            val aff = Calib3d.estimateAffinePartial2D(
                srcPts,
                dstPts,
                inliers,
                Calib3d.RANSAC,
                4.0,
                3000,
                0.99,
                10,
            )
            val inl = Core.countNonZero(inliers)
            inliers.release()
            if (!aff.empty() && inl >= MIN_INLIERS_AFFINE) {
                h = affineToHomography(aff)
                aff.release()
                inlierCount = inl
            } else {
                aff.release()
            }
        }
        srcPts.release()
        dstPts.release()
        val hom = h ?: return null

        val inlierFraction = inlierCount.toFloat() / good.size
        if (inlierCount < MIN_INLIERS_AFFINE || inlierFraction < MIN_INLIER_FRACTION) {
            hom.release()
            return null
        }

        val h22 = hom.get(2, 2)[0]
        if (abs(h22) < 1e-9) {
            hom.release()
            return null
        }
        Core.divide(hom, Scalar(h22), hom)
        val h00 = hom.get(0, 0)[0]
        val h01 = hom.get(0, 1)[0]
        val h10 = hom.get(1, 0)[0]
        val h11 = hom.get(1, 1)[0]
        val det = h00 * h11 - h01 * h10
        val scaleEst = sqrt(abs(det))
        val rotDeg = Math.toDegrees(atan2(h10, h00))
        val rotMod = ((rotDeg % 90.0) + 90.0) % 90.0
        val distToQuarterTurn = min(rotMod, 90.0 - rotMod)
        android.util.Log.i(
            TIMING_TAG,
            "pair good=${good.size} inl=$inlierCount frac=$inlierFraction scale=$scaleEst rot=$rotDeg",
        )
        // ML Kit pages can differ a bit in scale after independent crop/clean.
        if (det <= 0 || scaleEst < 0.35 || scaleEst > 2.8 || distToQuarterTurn > 35.0) {
            hom.release()
            return null
        }
        // Reject near-duplicate alignment (same page re-shot) — causes vertical
        // mis-stitches when a tiny bogus ty slips through.
        val tx = hom.get(0, 2)[0]
        val ty = hom.get(1, 2)[0]
        val move = hypot(tx, ty)
        val minMove = 0.12 * min(base.mat.cols(), base.mat.rows())
        if (move < minMove) {
            android.util.Log.i(TIMING_TAG, "pair rejected: near-duplicate move=$move < $minMove")
            hom.release()
            return null
        }
        return ScoredH(hom, inlierCount)
    }

    private fun affineToHomography(aff2x3: Mat): Mat {
        val h = Mat.eye(3, 3, CvType.CV_64F)
        h.put(0, 0, aff2x3.get(0, 0)[0], aff2x3.get(0, 1)[0], aff2x3.get(0, 2)[0])
        h.put(1, 0, aff2x3.get(1, 0)[0], aff2x3.get(1, 1)[0], aff2x3.get(1, 2)[0])
        return h
    }

    /** Prefer SIFT (in OpenCV 4.9 Maven AAR); ORB fallback. SCANS Stitcher not packaged in AAR. */
    private fun createDetector(): Feature2D {
        return try {
            // Lower contrastThreshold → more keypoints on cleaned white paper.
            SIFT.create(0, 3, 0.02, 10.0, 1.6)
        } catch (_: Throwable) {
            ORB.create(4000)
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
        val fill = Core.countNonZero(blob).toDouble() / (tile.cols().toDouble() * tile.rows())
        // ML Kit (and similar) already return cropped pages filling the frame —
        // re-rectify / mask destroys geometry needed for multi-tile stitch.
        if (fill >= 0.78) {
            android.util.Log.i(TIMING_TAG, "maskToPaper skip (pre-cropped fill=$fill)")
            blob.release()
            return tile
        }
        t = android.os.SystemClock.elapsedRealtime()
        val rectified = runCatching { rectifyFullPage(tile, blob) }.getOrNull()
        android.util.Log.i(
            TIMING_TAG,
            "maskToPaper seg=${segMs}ms rectify=${android.os.SystemClock.elapsedRealtime() - t}ms " +
                "rectified=${rectified != null} fill=$fill",
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
     * Vertical page stack for multi-page / non-overlapping ML Kit captures.
     * Owns a new Mat; does not take ownership of [pages].
     */
    private fun stackPages(pages: List<Mat>): Mat? {
        if (pages.isEmpty()) return null
        if (pages.size == 1) return pages[0].clone()
        val targetW = pages.maxOf { it.cols() }.coerceAtLeast(1)
        val resized = ArrayList<Mat>(pages.size)
        var totalH = 0
        for (p in pages) {
            val scale = targetW.toDouble() / p.cols().coerceAtLeast(1)
            val h = max(1, (p.rows() * scale).toInt())
            val r = Mat()
            Imgproc.resize(p, r, Size(targetW.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            resized.add(r)
            totalH += h
        }
        val out = Mat.zeros(totalH, targetW, pages.first().type())
        var y = 0
        for (r in resized) {
            val roi = out.rowRange(y, y + r.rows())
            r.copyTo(roi)
            y += r.rows()
            r.release()
        }
        return out
    }

    /**
     * Heuristic: similar aspect + size → likely overlapping tiles of one sheet
     * (not distinct multi-page docs). Stacking those duplicates content.
     */
    private fun looksLikeSamePageTiles(frames: List<CaptureFrame>): Boolean {
        if (frames.size < 2) return false
        val aspects = frames.map {
            val w = it.width.coerceAtLeast(1).toFloat()
            val h = it.height.coerceAtLeast(1).toFloat()
            w / h
        }
        val areas = frames.map {
            it.width.toLong().coerceAtLeast(1) * it.height.toLong().coerceAtLeast(1)
        }
        val a0 = aspects[0]
        val area0 = areas[0].toDouble()
        return aspects.all { abs(it - a0) < 0.12f } &&
            areas.all { abs(it - area0) / area0 < 0.35 }
    }

    /**
     * Reject tall-skinny mis-stitches (audit mosaic 970×4800 / 2270×4537 from portrait tiles).
     */
    private fun isSensibleMosaic(mosaic: Mat, ref: Mat): Boolean {
        val aM = mosaic.cols().toFloat() / mosaic.rows().coerceAtLeast(1)
        val aR = ref.cols().toFloat() / ref.rows().coerceAtLeast(1)
        val hGain = mosaic.rows().toFloat() / ref.rows().coerceAtLeast(1)
        val wGain = mosaic.cols().toFloat() / ref.cols().coerceAtLeast(1)
        if (aM < aR * 0.55f && hGain > 1.2f) {
            android.util.Log.i(
                TIMING_TAG,
                "reject mosaic tall-skinny aM=$aM aR=$aR ${mosaic.cols()}x${mosaic.rows()}",
            )
            return false
        }
        // Portrait page tiles panned L/R should grow width at least as much as height.
        if (aR < 1f && hGain > 1.3f && hGain > wGain * 1.05f) {
            android.util.Log.i(
                TIMING_TAG,
                "reject mosaic vertical-grow hGain=$hGain wGain=$wGain",
            )
            return false
        }
        val areaGain =
            (mosaic.cols().toLong() * mosaic.rows()) /
                (ref.cols().toLong() * ref.rows()).toFloat()
        if (areaGain < 1.1f && mosaic.cols() < ref.cols() * 1.08f) {
            android.util.Log.i(TIMING_TAG, "reject mosaic no expansion gain=$areaGain")
            return false
        }
        return true
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

    /** Load without paper mask/rectify — for ML Kit pages already cropped. */
    private fun loadMatRaw(uri: Uri): Mat? {
        val path = uri.path ?: return null
        val full = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (!full.empty()) {
            val work = ensureMaxLongEdge(full, WORK_LONG_EDGE)
            if (work !== full) full.release()
            return work
        }
        full.release()
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
        return ensureMaxLongEdge(bgr, WORK_LONG_EDGE).also { if (it !== bgr) bgr.release() }
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
        private const val MAX_MOSAIC_LONG_EDGE = 4800
        private const val MAX_CANVAS_SIDE = 10000
        private const val MAX_CANVAS_PIXELS = 36_000_000L

        /** Prefer matching against the last N accepted neighbors before older anchors. */
        private const val NEIGHBOR_WINDOW = 8

        /**
         * Pair acceptance — softened for ML Kit cleaned pages (fewer distinctive
         * keypoints than raw camera tiles). Affine path uses a slightly lower floor.
         */
        private const val MIN_GOOD_MATCHES = 8
        private const val MIN_INLIERS = 12
        private const val MIN_INLIERS_AFFINE = 10
        private const val MIN_INLIER_FRACTION = 0.08f

        private const val TIMING_TAG = "StitchTiming"

        fun sampleSizeFor(w: Int, h: Int, maxLong: Int): Int {
            var sample = 1
            var long = max(w, h)
            while (long / sample > maxLong) sample *= 2
            return sample
        }
    }
}
