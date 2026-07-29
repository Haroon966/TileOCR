package com.paperpanorama.ocr.capture

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/** Laplacian variance — higher = sharper. */
object Sharpness {
    fun laplacianVariance(bgrOrGray: Mat): Float {
        val gray = if (bgrOrGray.channels() == 1) {
            bgrOrGray
        } else {
            Mat().also { Imgproc.cvtColor(bgrOrGray, it, Imgproc.COLOR_BGR2GRAY) }
        }
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val s = std.toArray().firstOrNull() ?: 0.0
        mean.release()
        std.release()
        lap.release()
        if (gray !== bgrOrGray) gray.release()
        return (s * s).toFloat()
    }

    /** Reject stills softer than this (empirically OK for downscaled ~1200px docs). */
    const val MIN_ACCEPT = 25f
}
