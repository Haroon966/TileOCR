package com.paperpanorama.ocr.domain

/** Normalized 0..1 point in the camera preview / analysis frame. */
data class NormPoint(val x: Float, val y: Float)

/** One of four paper tiles as a normalized quad (TL, TR, BR, BL order). */
data class NormQuad(
    val tl: NormPoint,
    val tr: NormPoint,
    val br: NormPoint,
    val bl: NormPoint,
) {
    fun asList(): List<NormPoint> = listOf(tl, tr, br, bl)

    fun center(): NormPoint = NormPoint(
        (tl.x + tr.x + br.x + bl.x) / 4f,
        (tl.y + tr.y + br.y + bl.y) / 4f,
    )
}

/**
 * Live ImageAnalysis coach — paper outline, 2×2 tile quads, cut-off, IoU, features,
 * and which tile is currently centered in the viewfinder.
 */
data class LivePageHint(
    val paperQuad: List<NormPoint> = emptyList(),
    /** Four tile quads in paper space, order TL TR BL BR. */
    val tileQuads: List<NormQuad> = emptyList(),
    val cutOffTop: Boolean = false,
    val cutOffBottom: Boolean = false,
    val cutOffLeft: Boolean = false,
    val cutOffRight: Boolean = false,
    val pullBack: Boolean = false,
    val iouStable: Boolean = false,
    val featureCount: Int = 0,
    val featuresOk: Boolean = true,
    val hint: String? = null,
    /** Tile index (0..3) whose region best matches the viewfinder center, or -1. */
    val focusedTileIndex: Int = -1,
    /** True when the active tile region is large enough and centered for capture. */
    val activeTileAligned: Boolean = false,
) {
    val anyCutOff: Boolean
        get() = cutOffTop || cutOffBottom || cutOffLeft || cutOffRight

    companion object {
        val Idle = LivePageHint()
        const val MIN_FEATURES = 40
    }
}
