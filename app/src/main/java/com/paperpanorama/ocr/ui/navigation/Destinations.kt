package com.paperpanorama.ocr.ui.navigation

sealed class Destinations(val route: String) {
    data object Home : Destinations("home")
    data object Camera : Destinations("camera")
    data object Stitching : Destinations("stitching")
    data object StitchReview : Destinations("stitch_review")
    data object Prepare : Destinations("prepare")
    data object OcrReady : Destinations("ocr_ready")
    data object OcrResult : Destinations("ocr_result")
    data object VisionResult : Destinations("vision_result")
}
