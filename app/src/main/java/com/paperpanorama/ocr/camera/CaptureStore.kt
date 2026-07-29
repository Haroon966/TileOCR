package com.paperpanorama.ocr.camera

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.stitch.OpenCvBootstrap
import com.paperpanorama.ocr.util.BitmapDecode
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.UUID

class CaptureStore(private val context: Context) {
    fun newSessionId(): String = UUID.randomUUID().toString()

    fun sessionDir(sessionId: String): File =
        File(context.cacheDir, "capture/$sessionId").also { it.mkdirs() }

    fun createTileFile(sessionId: String, index: Int): File =
        File(sessionDir(sessionId), "tile_%02d.jpg".format(index))

    fun clearSession(sessionId: String) {
        sessionDir(sessionId).deleteRecursively()
    }

    fun frameFromFile(
        index: Int,
        file: File,
        displayRotation: Int,
    ): CaptureFrame {
        val (w, h) = BitmapDecode.bounds(file.absolutePath) ?: (0 to 0)
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val features = countFeatures(file)
        return CaptureFrame(
            index = index,
            uri = Uri.fromFile(file),
            width = w,
            height = h,
            displayRotation = displayRotation,
            exifOrientation = orientation,
            featureCount = features,
        )
    }

    fun countFeatures(file: File): Int {
        if (!OpenCvBootstrap.ensureInitialized()) return -1
        val bmp = BitmapDecode.decodeDownsampled(file.absolutePath, FEATURE_LONG_EDGE) ?: return 0
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        bmp.recycle()
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        mat.release()
        val orb = ORB.create(800)
        val kp = MatOfKeyPoint()
        orb.detect(gray, kp)
        val count = kp.toArray().size
        kp.release()
        gray.release()
        return count
    }

    companion object {
        private const val FEATURE_LONG_EDGE = 800
    }
}
