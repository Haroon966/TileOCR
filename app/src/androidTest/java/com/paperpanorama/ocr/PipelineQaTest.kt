package com.paperpanorama.ocr

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperpanorama.ocr.doc.OpenCvDocumentProcessor
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.domain.StitchResult
import com.paperpanorama.ocr.orient.OpenCvFrameNormalizer
import com.paperpanorama.ocr.stitch.OpenCvDocumentStitcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device QA: replays saved capture sessions (pushed to the app's external
 * files dir as qa_sessions/<name>/norm_*.jpg) through the real stitch ->
 * quad-detect -> prepare pipeline, saving outputs and logging timings.
 *
 * Push inputs first:
 *   adb push /tmp/stitchdbg/qa/norm_*.jpg /sdcard/Android/data/com.paperpanorama.ocr/files/qa_sessions/qa/
 */
@RunWith(AndroidJUnit4::class)
class PipelineQaTest {

    @Test
    fun rescanSavedSessions(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(ctx.getExternalFilesDir(null), "qa_sessions")
        val outDir = File(ctx.getExternalFilesDir(null), "qa_out").apply { mkdirs() }
        val filter = InstrumentationRegistry.getArguments().getString("sessions")
            ?.split(",")?.map { it.trim() }?.toSet()
        val sessions = base.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
            .filter { filter == null || it.name in filter }
        assertTrue("No sessions found in $base — push them first", sessions.isNotEmpty())

        val stitcher = OpenCvDocumentStitcher(ctx, OpenCvFrameNormalizer(ctx))
        val processor = OpenCvDocumentProcessor(ctx)

        for (dir in sessions) {
            val tiles = dir.listFiles { f -> f.name.matches(Regex("norm_\\d+\\.jpg")) }
                ?.sortedBy { it.name }.orEmpty()
            if (tiles.isEmpty()) continue
            // Copy tiles to a scratch dir: the stitcher/normalizer may write
            // siblings, and reruns should start from pristine inputs.
            val work = File(ctx.cacheDir, "qa_${dir.name}").apply {
                deleteRecursively()
                mkdirs()
            }
            val frames = tiles.mapIndexed { i, f ->
                val copy = File(work, f.name)
                f.copyTo(copy, overwrite = true)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(copy.absolutePath, bounds)
                CaptureFrame(
                    index = i,
                    uri = Uri.fromFile(copy),
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    displayRotation = 0,
                    exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                )
            }

            var t = SystemClock.elapsedRealtime()
            val result = stitcher.stitch(frames, { _, _ -> }, { false })
            val stitchMs = SystemClock.elapsedRealtime() - t
            if (result !is StitchResult.Ok) {
                Log.e(TAG, "${dir.name}: stitch FAILED after ${stitchMs}ms: $result")
                continue
            }
            result.mosaicUri.path?.let { File(it).copyTo(File(outDir, "${dir.name}_mosaic.jpg"), overwrite = true) }

            t = SystemClock.elapsedRealtime()
            val quad = processor.detectQuad(result.mosaicUri)
            val quadMs = SystemClock.elapsedRealtime() - t

            t = SystemClock.elapsedRealtime()
            val page = processor.prepareForOcr(result.mosaicUri, quad, EnhancePreset.Auto)
            val prepMs = SystemClock.elapsedRealtime() - t
            page.pageUri.path?.let { File(it).copyTo(File(outDir, "${dir.name}_page.jpg"), overwrite = true) }

            Log.i(
                TAG,
                "${dir.name}: tiles=${tiles.size} stitch=${stitchMs}ms quad=${quadMs}ms " +
                    "prepare=${prepMs}ms page=${page.width}x${page.height} quad=$quad",
            )
        }
        Log.i(TAG, "outputs in $outDir")
    }

    private companion object {
        const val TAG = "PipelineQA"
    }
}
