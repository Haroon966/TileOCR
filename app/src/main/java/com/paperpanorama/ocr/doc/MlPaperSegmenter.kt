package com.paperpanorama.ocr.doc

import android.content.Context
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * u2netp salient-object segmentation for the capture pipeline. Fixes the
 * paper-on-similar-color-background case the classic HSV mask can't separate
 * (validated offline on saved device sessions: consistently tighter masks,
 * no regressions).
 *
 * Not used for the live preview — inference is a few hundred ms per frame.
 * Callers fall back to [PaperMask.paperBlob] when this returns null.
 */
object MlPaperSegmenter {

    /**
     * 255-on-paper mask (same size as [bgr]) or null when the model is
     * unavailable, inference fails, or the blob fails sanity checks.
     */
    fun paperBlob(context: Context, bgr: Mat): Mat? = try {
        segment(context, bgr)
    } catch (t: Throwable) {
        Log.w(TAG, "ML segmentation failed, falling back", t)
        null
    }

    private fun segment(context: Context, bgr: Mat): Mat? {
        val interp = interpreter(context) ?: return null
        val fullW = bgr.cols()
        val fullH = bgr.rows()

        val input = Mat()
        Imgproc.resize(bgr, input, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val inputBuffer = preprocess(input)
        input.release()

        val outputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        synchronized(interp) {
            interp.runForMultipleInputsOutputs(
                arrayOf<Any>(inputBuffer),
                mapOf<Int, Any>(fusedOutputIndex to outputBuffer),
            )
        }

        val mask320 = thresholdSaliency(outputBuffer) ?: return null

        // Refine at <=1000px (kernel sizes are tuned for that), then upscale.
        val workLong = max(fullW, fullH).coerceAtMost(1000)
        val ws = workLong.toDouble() / max(fullW, fullH)
        val workW = (fullW * ws).toInt().coerceAtLeast(1)
        val workH = (fullH * ws).toInt().coerceAtLeast(1)
        val work = Mat()
        Imgproc.resize(mask320, work, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        mask320.release()

        val blob = PaperMask.refineBlob(work) ?: return null

        // Near-full-frame blob means the model saw no separable paper.
        val frac = Core.countNonZero(blob).toDouble() / (workW.toLong() * workH)
        if (frac > MAX_BLOB_FRAC) {
            blob.release()
            return null
        }

        if (workW == fullW && workH == fullH) return blob
        val full = Mat()
        Imgproc.resize(blob, full, Size(fullW.toDouble(), fullH.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        blob.release()
        return full
    }

    /** BGR 320x320 Mat -> normalized RGB float NHWC buffer (u2net preprocessing). */
    private fun preprocess(bgr320: Mat): ByteBuffer {
        val pixels = ByteArray(INPUT_SIZE * INPUT_SIZE * 3)
        bgr320.get(0, 0, pixels)
        var maxV = 1f
        for (b in pixels) {
            val v = (b.toInt() and 0xFF).toFloat()
            if (v > maxV) maxV = v
        }
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        var i = 0
        while (i < pixels.size) {
            val b = (pixels[i].toInt() and 0xFF) / maxV
            val g = (pixels[i + 1].toInt() and 0xFF) / maxV
            val r = (pixels[i + 2].toInt() and 0xFF) / maxV
            fb.put((r - MEAN_R) / STD_R)
            fb.put((g - MEAN_G) / STD_G)
            fb.put((b - MEAN_B) / STD_B)
            i += 3
        }
        return buf
    }

    /** Min-max normalize the saliency map and threshold at 0.5 into an 8U mask. */
    private fun thresholdSaliency(outputBuffer: ByteBuffer): Mat? {
        outputBuffer.rewind()
        val sal = FloatArray(INPUT_SIZE * INPUT_SIZE)
        outputBuffer.asFloatBuffer().get(sal)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in sal) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val range = mx - mn
        if (range < 1e-6f || mn.isNaN() || mx.isNaN()) return null
        val bytes = ByteArray(sal.size)
        for (j in sal.indices) {
            bytes[j] = if ((sal[j] - mn) / range > 0.5f) 255.toByte() else 0
        }
        val mask = Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_8UC1)
        mask.put(0, 0, bytes)
        return mask
    }

    @Volatile
    private var cached: Interpreter? = null
    private var fusedOutputIndex = 0
    private var loadFailed = false

    private fun interpreter(context: Context): Interpreter? {
        cached?.let { return it }
        if (loadFailed) return null
        synchronized(this) {
            cached?.let { return it }
            if (loadFailed) return null
            return try {
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buf.put(bytes)
                buf.rewind()
                val interp = Interpreter(buf, Interpreter.Options().setNumThreads(4))
                fusedOutputIndex = runCatching { interp.getOutputIndex(FUSED_OUTPUT_NAME) }.getOrDefault(0)
                cached = interp
                interp
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load $MODEL_ASSET", t)
                loadFailed = true
                null
            }
        }
    }

    private const val TAG = "MlPaperSegmenter"
    private const val MODEL_ASSET = "u2netp.tflite"
    private const val FUSED_OUTPUT_NAME = "PartitionedCall:0"
    private const val INPUT_SIZE = 320
    private const val MAX_BLOB_FRAC = 0.95

    private const val MEAN_R = 0.485f
    private const val MEAN_G = 0.456f
    private const val MEAN_B = 0.406f
    private const val STD_R = 0.229f
    private const val STD_G = 0.224f
    private const val STD_B = 0.225f
}
