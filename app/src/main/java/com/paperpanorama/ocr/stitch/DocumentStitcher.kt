package com.paperpanorama.ocr.stitch

import android.net.Uri
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.StitchResult

interface DocumentStitcher {
    /**
     * Stitch already-normalized frames. [onProgress] is 0f..1f.
     * Cooperative cancel via [isCancelled].
     */
    suspend fun stitch(
        frames: List<CaptureFrame>,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): StitchResult
}
