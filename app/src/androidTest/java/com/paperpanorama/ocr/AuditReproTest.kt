package com.paperpanorama.ocr

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paperpanorama.ocr.doc.OpenCvDocumentProcessor
import com.paperpanorama.ocr.doc.QuadMath
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.domain.StitchResult
import com.paperpanorama.ocr.orient.OpenCvFrameNormalizer
import com.paperpanorama.ocr.stitch.OpenCvDocumentStitcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Full-flow replay of the Shoaib 4-tile audit session (assets/audit_repro/).
 * Outputs → external files/repro_out/ for adb pull.
 */
@RunWith(AndroidJUnit4::class)
class AuditReproTest {

    @Test
    fun stitchPrepare_shoaib4AuditInputs(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val work = File(ctx.cacheDir, "audit_repro_work").apply {
            deleteRecursively()
            mkdirs()
        }
        val outDir = File(ctx.getExternalFilesDir(null), "repro_out").apply {
            deleteRecursively()
            mkdirs()
        }

        val frames = (0..3).map { i ->
            val name = "input_%02d.jpg".format(i)
            val dest = File(work, name)
            assets.open("audit_repro/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, bounds)
            CaptureFrame(
                index = i,
                uri = Uri.fromFile(dest),
                width = bounds.outWidth,
                height = bounds.outHeight,
                displayRotation = 0,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
            )
        }
        Log.i(TAG, "loaded frames=${frames.map { "${it.width}x${it.height}" }}")

        val stitcher = OpenCvDocumentStitcher(ctx, OpenCvFrameNormalizer(ctx))
        val processor = OpenCvDocumentProcessor(ctx)

        var t = SystemClock.elapsedRealtime()
        val result = stitcher.stitch(frames, { f, msg -> Log.i(TAG, "stitch $f $msg") }, { false })
        val stitchMs = SystemClock.elapsedRealtime() - t
        assertTrue("stitch failed: $result", result is StitchResult.Ok)
        val ok = result as StitchResult.Ok
        val mosaicFile = File(ok.mosaicUri.path!!)
        assertTrue(mosaicFile.exists())
        mosaicFile.copyTo(File(outDir, "mosaic.jpg"), overwrite = true)
        val mosaicBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(mosaicFile.absolutePath, mosaicBounds)
        Log.i(
            TAG,
            "stitch ok in ${stitchMs}ms usedFallback=${ok.usedFallback} " +
                "mosaic=${mosaicBounds.outWidth}x${mosaicBounds.outHeight}",
        )

        // Prefer full-frame for fallback single-page; else detect.
        val aspect = mosaicBounds.outWidth.toFloat() / mosaicBounds.outHeight.coerceAtLeast(1)
        val inputAspect = frames[0].width.toFloat() / frames[0].height.coerceAtLeast(1)
        val quad = if (ok.usedFallback || aspect < inputAspect * 0.5f) {
            QuadMath.fullFrame(mosaicBounds.outWidth, mosaicBounds.outHeight)
        } else {
            processor.detectQuad(ok.mosaicUri)
        }

        t = SystemClock.elapsedRealtime()
        val page = processor.prepareForOcr(ok.mosaicUri, quad, EnhancePreset.Auto)
        val prepMs = SystemClock.elapsedRealtime() - t
        page.pageUri.path?.let { File(it).copyTo(File(outDir, "page.jpg"), overwrite = true) }
        Log.i(TAG, "prepare ${prepMs}ms page=${page.width}x${page.height} out=$outDir")

        // Sanity: result should not be a tall skinny strip vs portrait inputs.
        val pageAspect = page.width.toFloat() / page.height.coerceAtLeast(1)
        assertTrue(
            "page aspect $pageAspect too skinny vs input $inputAspect " +
                "(${page.width}x${page.height})",
            pageAspect >= inputAspect * 0.55f,
        )
        // Single-page fallback should be roughly one tile, not a stacked strip.
        if (ok.usedFallback) {
            assertTrue(
                "fallback page too tall: ${page.height} vs input ${frames[0].height}",
                page.height < frames[0].height * 1.35f,
            )
        }
        assertNotNull(outDir.listFiles()?.firstOrNull { it.name == "mosaic.jpg" })
        File(outDir, "summary.txt").writeText(
            buildString {
                appendLine("stitchMs=$stitchMs usedFallback=${ok.usedFallback}")
                appendLine("mosaic=${mosaicBounds.outWidth}x${mosaicBounds.outHeight}")
                appendLine("page=${page.width}x${page.height}")
                appendLine("quad=$quad")
            },
        )
    }

    private companion object {
        const val TAG = "AuditRepro"
    }
}
