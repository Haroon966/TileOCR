package com.paperpanorama.ocr.session

sealed class ScanNavEvent {
    data object ToHome : ScanNavEvent()
    data object ToCamera : ScanNavEvent()
    data object ToStitching : ScanNavEvent()
    data object ToPrepare : ScanNavEvent()
    data object ToOcrReady : ScanNavEvent()
    data object RequestPermission : ScanNavEvent()
    /** User tapped Finish before coverage gates; show confirm dialog. */
    data object ConfirmEarlyFinish : ScanNavEvent()
    data class Snackbar(val message: String) : ScanNavEvent()
}
