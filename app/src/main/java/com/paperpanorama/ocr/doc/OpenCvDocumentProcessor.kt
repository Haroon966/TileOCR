package com.paperpanorama.ocr.doc

import android.content.Context
import android.net.Uri
import com.paperpanorama.ocr.domain.DocQuad
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.domain.PrepareResult
import com.paperpanorama.ocr.orient.OrientationMath
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import com.paperpanorama.ocr.util.BitmapDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class OpenCvDocumentProcessor(
    private val context: Context,
) {
    suspend fun detectQuad(imageUri: Uri): DocQuad = withContext(Dispatchers.Default) {
        if (!OpenCvBootstrap.ensureInitialized()) {
            return@withContext fallbackQuad(imageUri)
        }
        val path = imageUri.path ?: return@withContext fallbackQuad(imageUri)
        val bounds = BitmapDecode.bounds(path) ?: return@withContext fallbackQuad(imageUri)
        val (fullW, fullH) = bounds

        val detectLong = 1000
        val scale = detectLong.toDouble() / max(fullW, fullH).coerceAtLeast(1)
        val smallW = (fullW * scale).roundToInt().coerceAtLeast(1)
        val smallH = (fullH * scale).roundToInt().coerceAtLeast(1)

        val src = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (src.empty()) {
            src.release()
            return@withContext QuadMath.fullFrame(fullW, fullH)
        }
        val small = Mat()
        Imgproc.resize(src, small, Size(smallW.toDouble(), smallH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        src.release()

        // Bright-blob segmentation first: paper on a darker/busy background forms
        // one big bright region; edge contours get shredded by patterned cloth.
        val best = detectBySegmentation(small, smallW, smallH)
            ?: detectByEdges(small, smallW, smallH)
        small.release()

        val quad = best
            ?.let { QuadMath.scale(it, (1.0 / scale).toFloat(), (1.0 / scale).toFloat()) }
            ?.let { QuadMath.expand(it, QUAD_SAFETY_FRAC) }
            ?.let { QuadMath.clampToImage(it, fullW, fullH) }
            ?: QuadMath.fullFrame(fullW, fullH)
        quad
    }

    /**
     * Paper blob (ML segmentation, classic HSV fallback) → shared blob→quad
     * helper. Small-image coords.
     */
    private fun detectBySegmentation(small: Mat, w: Int, h: Int): DocQuad? {
        val blob = MlPaperSegmenter.paperBlob(context, small)
            ?: PaperMask.paperBlob(small)
            ?: return null
        val quad = PaperMask.quadFromBlob(blob)
        blob.release()
        return quad
    }

    /** Legacy Canny 4-gon search. Small-image coords. */
    private fun detectByEdges(small: Mat, w: Int, h: Int): DocQuad? {
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        gray.release()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        edges.release()

        val imgArea = (w * h).toDouble()
        var best: DocQuad? = null
        var bestArea = 0.0
        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            c2f.release()
            val pts = approx.toArray()
            approx.release()
            if (pts.size != 4) continue
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.15 || area > imgArea * 0.98) continue
            val poly = MatOfPoint(*pts)
            val convex = Imgproc.isContourConvex(poly)
            poly.release()
            if (!convex) continue
            if (area > bestArea) {
                bestArea = area
                best = QuadMath.orderCorners(pts.map { it.x.toFloat() to it.y.toFloat() })
            }
        }
        contours.forEach { it.release() }
        return best
    }

    suspend fun prepareForOcr(
        mosaicUri: Uri,
        quad: DocQuad,
        preset: EnhancePreset,
    ): PrepareResult = withContext(Dispatchers.Default) {
        check(OpenCvBootstrap.ensureInitialized()) { "OpenCV failed to load" }
        val path = mosaicUri.path ?: error("Bad mosaic path")
        val src = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        check(!src.empty()) { "Could not read mosaic" }

        val warped = warpPerspective(src, quad)
        src.release()
        // Masked-tile stitching leaves black outside the paper; render it as white
        // so the final page is clean. Real ink never photographs as pure black.
        fillBlackWithWhite(warped)

        val deskewed = deskew(warped)
        if (deskewed !== warped) warped.release()

        val enhanced = enhance(deskewed, preset)
        if (enhanced !== deskewed) deskewed.release()

        val outDir = File(context.cacheDir, "ocr_ready").also { it.mkdirs() }
        val out = File(outDir, "page_${System.currentTimeMillis()}.jpg")
        check(Imgcodecs.imwrite(out.absolutePath, enhanced)) { "Failed to write prepared page" }
        val w = enhanced.cols()
        val h = enhanced.rows()
        enhanced.release()
        PrepareResult(Uri.fromFile(out), w, h)
    }

    suspend fun rotateMosaic90(uri: Uri): Uri = withContext(Dispatchers.Default) {
        val path = uri.path ?: return@withContext uri
        if (!OpenCvBootstrap.ensureInitialized()) return@withContext uri
        val src = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
        if (src.empty()) {
            src.release()
            return@withContext uri
        }
        val dst = Mat()
        Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
        src.release()
        val out = File(path).parentFile?.resolve("rot90_${System.currentTimeMillis()}.jpg")
            ?: File(context.cacheDir, "rot90_${System.currentTimeMillis()}.jpg")
        val ok = Imgcodecs.imwrite(out.absolutePath, dst)
        dst.release()
        if (ok) Uri.fromFile(out) else uri
    }

    private fun warpPerspective(src: Mat, quad: DocQuad): Mat {
        val (outWf, outHf) = QuadMath.edgeLengths(quad)
        // Allow tall multi-page / panorama mosaics — old 4000 cap chopped stacked docs in half.
        val outW = outWf.roundToInt().coerceIn(200, MAX_PAGE_SIDE)
        val outH = outHf.roundToInt().coerceIn(200, MAX_PAGE_SIDE)
        val srcPts = MatOfPoint2f(
            Point(quad.tlX.toDouble(), quad.tlY.toDouble()),
            Point(quad.trX.toDouble(), quad.trY.toDouble()),
            Point(quad.brX.toDouble(), quad.brY.toDouble()),
            Point(quad.blX.toDouble(), quad.blY.toDouble()),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        srcPts.release()
        dstPts.release()
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, m, Size(outW.toDouble(), outH.toDouble()))
        m.release()
        return dst
    }

    private fun fillBlackWithWhite(bgr: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 5.0, 255.0, Imgproc.THRESH_BINARY_INV)
        gray.release()
        bgr.setTo(org.opencv.core.Scalar(255.0, 255.0, 255.0), mask)
        mask.release()
    }

    private fun deskew(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180.0, 80)
        gray.release()
        edges.release()
        if (lines.empty()) {
            lines.release()
            return bgr
        }
        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val data = lines.get(i, 0) ?: continue
            var deg = Math.toDegrees(data[1]) - 90.0
            while (deg > 45) deg -= 90
            while (deg < -45) deg += 90
            if (abs(deg) <= 15) angles.add(deg)
        }
        lines.release()
        if (angles.isEmpty()) return bgr
        val skew = OrientationMath.clampDeskew(angles.average())
        if (abs(skew) < 0.3) return bgr
        return rotateMat(bgr, -skew)
    }

    private fun enhance(bgr: Mat, preset: EnhancePreset): Mat = when (preset) {
        EnhancePreset.Original -> bgr.clone()
        EnhancePreset.Auto -> whitenAndSharpen(bgr)
        EnhancePreset.Contrast -> {
            val out = Mat()
            bgr.convertTo(out, -1, 1.25, 10.0)
            out
        }
        EnhancePreset.Bw -> {
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val bw = Mat()
            Imgproc.adaptiveThreshold(
                gray,
                bw,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                15,
                10.0,
            )
            gray.release()
            val bgrOut = Mat()
            Imgproc.cvtColor(bw, bgrOut, Imgproc.COLOR_GRAY2BGR)
            bw.release()
            bgrOut
        }
    }

    /**
     * CamScanner-style cleanup: per-channel illumination flattening (divide by the
     * morphologically estimated background) turns shadowed paper white while ink —
     * black, blue, red — stays saturated. Then a percentile contrast stretch on
     * luminance and an unsharp mask for crisp strokes.
     */
    private fun whitenAndSharpen(bgr: Mat): Mat {
        val channels = ArrayList<Mat>(3)
        Core.split(bgr, channels)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))
        for (ch in channels) {
            val bg = Mat()
            Imgproc.morphologyEx(ch, bg, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), 21.0)
            Core.divide(ch, bg, ch, 255.0)
            bg.release()
        }
        kernel.release()
        val white = Mat()
        Core.merge(channels, white)
        channels.forEach { it.release() }

        stretchLuminance(white)

        val blur = Mat()
        Imgproc.GaussianBlur(white, blur, Size(0.0, 0.0), 2.0)
        val sharp = Mat()
        Core.addWeighted(white, 1.5, blur, -0.5, 0.0, sharp)
        white.release()
        blur.release()
        return sharp
    }

    /** In-place 2nd–90th percentile stretch on the L channel. */
    private fun stretchLuminance(bgr: Mat) {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val l = channels[0]

        val hist = Mat()
        Imgproc.calcHist(listOf(l), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = l.total().toDouble()
        var cum = 0.0
        var lo = 0
        var hi = 255
        var foundLo = false
        for (i in 0 until 256) {
            cum += hist.get(i, 0)[0]
            if (!foundLo && cum >= total * 0.02) {
                lo = i
                foundLo = true
            }
            if (cum >= total * 0.90) {
                hi = i
                break
            }
        }
        hist.release()
        if (hi > lo) {
            val alpha = 255.0 / (hi - lo)
            l.convertTo(l, -1, alpha, -lo * alpha)
        }
        Core.merge(channels, lab)
        channels.forEach { it.release() }
        Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
        lab.release()
    }

    private fun rotateMat(src: Mat, degrees: Double): Mat {
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rot = Imgproc.getRotationMatrix2D(center, degrees, 1.0)
        val radians = Math.toRadians(degrees)
        val cosA = abs(cos(radians))
        val sinA = abs(sin(radians))
        val newW = (src.cols() * cosA + src.rows() * sinA).roundToInt().coerceAtLeast(1)
        val newH = (src.cols() * sinA + src.rows() * cosA).roundToInt().coerceAtLeast(1)
        rot.put(0, 2, rot.get(0, 2)[0] + (newW / 2.0 - center.x))
        rot.put(1, 2, rot.get(1, 2)[0] + (newH / 2.0 - center.y))
        val dst = Mat()
        Imgproc.warpAffine(
            src,
            dst,
            rot,
            Size(newW.toDouble(), newH.toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
        )
        rot.release()
        return dst
    }

    private fun fallbackQuad(uri: Uri): DocQuad {
        val path = uri.path ?: return QuadMath.fullFrame(1000, 1000)
        val (w, h) = BitmapDecode.bounds(path) ?: (1000 to 1000)
        return QuadMath.fullFrame(w, h)
    }

    companion object {
        /** Expand detected quad by 3% so glyphs touching the paper edge are never cropped. */
        private const val QUAD_SAFETY_FRAC = 0.03f

        /** Tall panoramas / stacked pages (was 4000 — chopped audit mosaics in half). */
        private const val MAX_PAGE_SIDE = 12000
    }
}
