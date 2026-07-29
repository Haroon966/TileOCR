package com.paperpanorama.ocr.domain

/** State of one of the four AR paper tiles (2×2). */
enum class QuadTileState {
    Pending,
    Good,
    Blurry,
}

/**
 * Guided 4-tile scan coach.
 * [readyToFinish] means all 4 tiles are Good — user may tap Done (never auto-advance).
 */
data class CoverageSnapshot(
    val coveragePercent: Int = 0,
    val qualityPercent: Int = 0,
    val nextHint: String = "Frame the whole page so all four tiles appear",
    val title: String = "Scan page",
    /** True only when all 4 tiles are Good — enables Done; does not auto-stitch. */
    val readyToFinish: Boolean = false,
    val acceptedTiles: Int = 0,
    val registrationFailed: Boolean = false,
    val lastShotBlurry: Boolean = false,
    val progressDeterminate: Boolean = false,
    val sawStartEnd: Boolean = false,
    val sawFinishEnd: Boolean = false,
    val noPaper: Boolean = false,
    val pullBack: Boolean = false,
    val cells: List<BandScanState> = emptyList(),
    val gridCols: Int = 2,
    val gridRows: Int = 2,
    val bands: List<BandScanState> = emptyList(),
    val longAxisVertical: Boolean = true,
    val nextTargetNorm: Float? = null,
    val nextTargetCrossNorm: Float? = null,
    val guideDirection: ScanGuideDirection = ScanGuideDirection.None,
    val lastShotDuplicate: Boolean = false,
    /** Four tile states: 0=TL, 1=TR, 2=BL, 3=BR. */
    val tileStates: List<QuadTileState> = List(4) { QuadTileState.Pending },
    /** Tile the system wants the user to capture next (0..3). */
    val activeTileIndex: Int = 0,
    val goodTileCount: Int = 0,
    /** Whole page was framed so the 2×2 map is valid. */
    val pageMapped: Boolean = false,
) {
    companion object {
        val Idle = CoverageSnapshot()
        const val TILE_COUNT = 4

        fun tileLabel(index: Int): String = when (index) {
            0 -> "Top left"
            1 -> "Top right"
            2 -> "Bottom left"
            3 -> "Bottom right"
            else -> "Tile ${index + 1}"
        }
    }
}

enum class ScanGuideDirection {
    None,
    Up,
    Down,
    Left,
    Right,
    Hold,
}

enum class BandScanState {
    Empty,
    Soft,
    Locked,
}
