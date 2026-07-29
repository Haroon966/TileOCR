package com.paperpanorama.ocr.orient

import android.net.Uri
import com.paperpanorama.ocr.domain.CaptureFrame

interface FrameNormalizer {
    /** Bake EXIF/device rotation into pixels for stitch input. */
    suspend fun normalizeForStitch(frame: CaptureFrame): CaptureFrame

    /** Content-based 90° snap + fine deskew; returns new Uri. */
    suspend fun autoUprightAndDeskew(mosaicUri: Uri): Uri
}
