package com.paperpanorama.ocr.session

sealed class ScanNavEvent {
    data object ToHome : ScanNavEvent()
    /** Pop to Home, then launch ML Kit document scanner. */
    data object ToCamera : ScanNavEvent()
    /** Open guided CameraX capture screen (ML Kit fallback / tile mode). */
    data object ToGuidedCamera : ScanNavEvent()
    data object ToStitching : ScanNavEvent()
    data object ToStitchReview : ScanNavEvent()
    data object ToPrepare : ScanNavEvent()
    data object ToOcrReady : ScanNavEvent()
    data object ToOcrResult : ScanNavEvent()
    data object ToVisionResult : ScanNavEvent()
    data object RequestPermission : ScanNavEvent()
    /** User tapped Finish before coverage gates; show confirm dialog. */
    data object ConfirmEarlyFinish : ScanNavEvent()
    data class Snackbar(val message: String) : ScanNavEvent()
}
