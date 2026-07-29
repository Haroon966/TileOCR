package com.paperpanorama.ocr.session

sealed class ScanNavEvent {
    data object ToHome : ScanNavEvent()
    data object ToCamera : ScanNavEvent()
    data object ToStitching : ScanNavEvent()
    data object ToPrepare : ScanNavEvent()
    data object ToOcrReady : ScanNavEvent()
    data object RequestPermission : ScanNavEvent()
    data class Snackbar(val message: String) : ScanNavEvent()
}
