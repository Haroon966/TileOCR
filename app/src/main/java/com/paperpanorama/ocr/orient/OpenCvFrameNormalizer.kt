package com.paperpanorama.ocr.orient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class OpenCvFrameNormalizer(
    private val context: Context,
) : FrameNormalizer {

    override suspend fun normalizeForStitch(frame: CaptureFrame): CaptureFrame =
        withContext(Dispatchers.Default) {
            val path = pathOf(frame.uri) ?: return@withContext frame
            var bitmap = decodeWithExif(path) ?: return@withContext frame
            // EXIF first; if missing, apply CameraX Surface.ROTATION_* (or degrees).
            val exifOrient = runCatching {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val exifApplied = exifOrient != ExifInterface.ORIENTATION_NORMAL &&
                exifOrient != ExifInterface.ORIENTATION_UNDEFINED
            if (!exifApplied) {
                val rot = OrientationMath.displayRotationToDegrees(frame.displayRotation)
                if (rot != 0) {
                    val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                    val turned = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
                    )
                    if (turned != bitmap) bitmap.recycle()
                    bitmap = turned
                }
            }
            val w = bitmap.width
            val h = bitmap.height
            val outFile = File(path).parentFile?.resolve("norm_${frame.index}.jpg")
                ?: File(context.cacheDir, "norm_${frame.index}.jpg")
            writeJpeg(bitmap, outFile)
            bitmap.recycle()
            frame.copy(
                uri = Uri.fromFile(outFile),
                width = w,
                height = h,
                displayRotation = 0,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            )
        }

    /**
     * Fine deskew only. Orientation comes from the device's gravity sensor at
     * capture time (EXIF, applied in [normalizeForStitch]) — that's ground truth.
     * Content-based quarter-turn guessing rotated correct pages sideways on
     * handwriting, so it was removed.
     */
    override suspend fun autoUprightAndDeskew(mosaicUri: Uri): Uri =
        withContext(Dispatchers.Default) {
            if (!OpenCvBootstrap.ensureInitialized()) return@withContext mosaicUri
            val path = pathOf(mosaicUri) ?: return@withContext mosaicUri
            try {
                val full = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
                if (full.empty()) {
                    full.release()
                    return@withContext mosaicUri
                }

                var working = full

                // Fine deskew angle from small preview only
                val preview = downscaleColor(working, SCORE_LONG_EDGE)
                val fine = estimateSkewDegrees(preview)
                preview.release()
                val clamped = OrientationMath.clampDeskew(fine)
                if (abs(clamped) > 0.3) {
                    val deskewed = rotateMat(working, -clamped)
                    working.release()
                    working = deskewed
                }

                val outFile = File(path).parentFile?.resolve("upright_${System.currentTimeMillis()}.jpg")
                    ?: File(context.cacheDir, "upright_${System.currentTimeMillis()}.jpg")
                val ok = Imgcodecs.imwrite(outFile.absolutePath, working)
                working.release()
                if (!ok) return@withContext mosaicUri
                Uri.fromFile(outFile)
            } catch (_: Throwable) {
                mosaicUri
            }
        }

    /** Manual 90° clockwise rotate for Review override. */
    suspend fun rotateClockwise90(uri: Uri): Uri = withContext(Dispatchers.Default) {
        val path = pathOf(uri) ?: return@withContext uri
        try {
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
            if (!ok) uri else Uri.fromFile(out)
        } catch (_: Throwable) {
            // Bitmap fallback
            val bmp = BitmapFactory.decodeFile(path) ?: return@withContext uri
            val matrix = Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            val out = File(path).parentFile?.resolve("rot90_${System.currentTimeMillis()}.jpg")
                ?: File(context.cacheDir, "rot90_${System.currentTimeMillis()}.jpg")
            writeJpeg(rotated, out)
            rotated.recycle()
            Uri.fromFile(out)
        }
    }

    private fun decodeWithExif(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = false }
        val raw = BitmapFactory.decodeFile(path, options) ?: return null
        val exif = runCatching { ExifInterface(path) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return raw
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (out != raw) raw.recycle()
        return out
    }

    private fun estimateSkewDegrees(bgr: Mat): Double {
        val gray = Mat()
        if (bgr.channels() > 1) {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            bgr.copyTo(gray)
        }
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180.0, 80)
        gray.release()
        edges.release()
        if (lines.empty() || lines.rows() == 0) {
            lines.release()
            return 0.0
        }
        val angles = mutableListOf<Double>()
        for (i in 0 until lines.rows()) {
            val data = lines.get(i, 0) ?: continue
            val theta = data[1]
            var deg = Math.toDegrees(theta) - 90.0
            while (deg > 45) deg -= 90
            while (deg < -45) deg += 90
            if (abs(deg) <= 15) angles.add(deg)
        }
        lines.release()
        if (angles.isEmpty()) return 0.0
        return angles.average()
    }

    private fun rotateMat(src: Mat, degrees: Double): Mat {
        if (abs(degrees) < 0.01) return src.clone()
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

    private fun downscaleGray(src: Mat, longEdge: Int): Mat {
        val long = max(src.cols(), src.rows())
        if (long <= longEdge) return src.clone()
        val scale = longEdge.toDouble() / long
        val dst = Mat()
        Imgproc.resize(src, dst, Size(src.cols() * scale, src.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return dst
    }

    private fun downscaleColor(src: Mat, longEdge: Int): Mat = downscaleGray(src, longEdge)

    private fun pathOf(uri: Uri): String? {
        return when {
            uri.scheme == "file" -> uri.path
            uri.scheme == null -> uri.toString()
            else -> uri.path
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        runCatching {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        }
    }

    companion object {
        private const val SCORE_LONG_EDGE = 1200
    }
}
