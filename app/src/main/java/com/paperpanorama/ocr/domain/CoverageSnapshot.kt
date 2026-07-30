package com.paperpanorama.ocr.domain

/**
 * Whole-page 8×12 guided scan coach.
 * [readyToFinish] = grid fully Locked, or ≥50% locked with ≥2 stills — user may Done anytime in camera UI.
 */
data class CoverageSnapshot(
    val coveragePercent: Int = 0,
    val qualityPercent: Int = 0,
    val nextHint: String = "Frame the whole page so the grid can lock on",
    val title: String = "Scan page",
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
    val gridCols: Int = GRID_COLS,
    val gridRows: Int = GRID_ROWS,
    val bands: List<BandScanState> = emptyList(),
    val longAxisVertical: Boolean = true,
    val nextTargetNorm: Float? = null,
    val nextTargetCrossNorm: Float? = null,
    val guideDirection: ScanGuideDirection = ScanGuideDirection.None,
    val lastShotDuplicate: Boolean = false,
    /** Legacy alias: Locked→Good, Soft→Blurry, Empty→Pending (for old UI chips). */
    val tileStates: List<QuadTileState> = emptyList(),
    val activeTileIndex: Int = 0,
    val goodTileCount: Int = 0,
    val pageMapped: Boolean = false,
    val lockedCellCount: Int = 0,
    val softCellCount: Int = 0,
    val emptyCellCount: Int = 0,
) {
    companion object {
        val Idle = CoverageSnapshot()
        const val GRID_COLS = 8
        const val GRID_ROWS = 12
        const val TILE_COUNT = GRID_COLS * GRID_ROWS
        const val MAX_LOCKED_OVERLAP = 0.30f

        fun tileLabel(index: Int): String = "Cell ${index + 1}"
    }
}

/** Legacy 2×2 naming kept for overlay chips that still map cell severity. */
enum class QuadTileState {
    Pending,
    Good,
    Blurry,
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
