package com.paperpanorama.ocr.domain

/** Normalized 0..1 point in the camera preview / analysis frame. */
data class NormPoint(val x: Float, val y: Float)

/** Cell / paper quad in normalized preview coords (TL, TR, BR, BL). */
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

    fun width(): Float = kotlin.math.abs(tr.x - tl.x).coerceAtLeast(
        kotlin.math.abs(br.x - bl.x),
    )

    fun height(): Float = kotlin.math.abs(bl.y - tl.y).coerceAtLeast(
        kotlin.math.abs(br.y - tr.y),
    )
}

/**
 * Live coach — paper outline, persistent 8×12 cell quads (seed-tracked), alignment.
 */
data class LivePageHint(
    val paperQuad: List<NormPoint> = emptyList(),
    /** Cell quads in row-major order (gridCols * gridRows), preview-normalized. */
    val tileQuads: List<NormQuad> = emptyList(),
    val gridCols: Int = CoverageSnapshot.GRID_COLS,
    val gridRows: Int = CoverageSnapshot.GRID_ROWS,
    val cutOffTop: Boolean = false,
    val cutOffBottom: Boolean = false,
    val cutOffLeft: Boolean = false,
    val cutOffRight: Boolean = false,
    val pullBack: Boolean = false,
    val iouStable: Boolean = false,
    val featureCount: Int = 0,
    val featuresOk: Boolean = true,
    val hint: String? = null,
    val focusedTileIndex: Int = -1,
    val activeTileAligned: Boolean = false,
    val pageMapped: Boolean = false,
    /** Tracking lost while zoomed — overlay uses last good pose. */
    val trackingLost: Boolean = false,
) {
    val anyCutOff: Boolean
        get() = cutOffTop || cutOffBottom || cutOffLeft || cutOffRight

    companion object {
        val Idle = LivePageHint(featuresOk = false)
        const val MIN_FEATURES = 40
    }
}
