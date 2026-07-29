package com.paperpanorama.ocr.domain

import android.net.Uri

enum class CaptureMode {
    Single,
    Panorama,
}

data class CaptureFrame(
    val index: Int,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val displayRotation: Int,
    val exifOrientation: Int,
    val featureCount: Int = 0,
)

sealed class StitchResult {
    data class Ok(val mosaicUri: Uri, val usedFallback: Boolean = false) : StitchResult()
    data class Failed(val reason: String, val bestFrameUri: Uri?) : StitchResult()
}

sealed class StitchProgress {
    data class Running(val fraction: Float, val message: String) : StitchProgress()
    data object Cancelled : StitchProgress()
}
