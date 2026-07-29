package com.paperpanorama.ocr.capture

import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.QuadTileState
import com.paperpanorama.ocr.domain.ScanGuideDirection

/**
 * Fixed 2×2 paper tile enrollment (JVM-testable).
 *
 * Phase A — [pageMapped]: whole page seen so the 2×2 map is trustworthy.
 * Phase B — capture each tile; blurry tiles stay active for recapture.
 * Done only when all four are Good (UI never auto-advances).
 */
class PageCoverageModel {
    private val states = Array(TILE_COUNT) { QuadTileState.Pending }
    private var active = 0
    private var acceptedTiles = 0
    private var lastShotBlurry = false
    private var noPaper = false
    private var pullBack = false
    private var registrationFailed = false
    /** Whole page framed at least once (live or still). */
    private var pageMapped = false

    fun reset() {
        for (i in states.indices) states[i] = QuadTileState.Pending
        active = 0
        acceptedTiles = 0
        lastShotBlurry = false
        noPaper = false
        pullBack = false
        registrationFailed = false
        pageMapped = false
    }

    fun markPageMapped() {
        pageMapped = true
        pullBack = false
        noPaper = false
    }

    fun isPageMapped(): Boolean = pageMapped

    fun markNoPaper() {
        noPaper = true
        pullBack = false
        lastShotBlurry = false
    }

    fun markPullBack() {
        pullBack = true
        noPaper = false
        lastShotBlurry = false
    }

    fun markBlurry() {
        rejectBlurry(active)
    }

    fun markRegistrationFailed() {
        registrationFailed = true
        lastShotBlurry = false
    }

    fun activeTileIndex(): Int = active

    fun tileState(index: Int): QuadTileState =
        states.getOrElse(index) { QuadTileState.Pending }

    fun acceptTile(tileIndex: Int = active) {
        val i = tileIndex.coerceIn(0, TILE_COUNT - 1)
        states[i] = QuadTileState.Good
        acceptedTiles = states.count { it == QuadTileState.Good }
        lastShotBlurry = false
        noPaper = false
        pullBack = false
        registrationFailed = false
        pageMapped = true
        active = nextWorkIndex()
    }

    fun rejectBlurry(tileIndex: Int = active) {
        val i = tileIndex.coerceIn(0, TILE_COUNT - 1)
        states[i] = QuadTileState.Blurry
        active = i
        lastShotBlurry = true
        registrationFailed = false
    }

    fun clearTile(tileIndex: Int) {
        val i = tileIndex.coerceIn(0, TILE_COUNT - 1)
        states[i] = QuadTileState.Pending
        acceptedTiles = states.count { it == QuadTileState.Good }
        active = nextWorkIndex()
        lastShotBlurry = false
    }

    fun allGood(): Boolean = states.all { it == QuadTileState.Good }

    fun goodCount(): Int = states.count { it == QuadTileState.Good }

    fun snapshot(): CoverageSnapshot {
        val good = goodCount()
        val all = allGood()
        val label = CoverageSnapshot.tileLabel(active)
        val hint = when {
            lastShotBlurry ->
                "Blurry — hold still on $label and recapture (no need to pan away)"
            noPaper ->
                "Can't see paper — change background or lighting"
            pullBack && !pageMapped ->
                "Pull back so the whole page shows — then we'll split it into 4 tiles"
            pullBack ->
                "Show more of the page edges, then move into $label"
            !pageMapped ->
                "Frame the whole page in view so all 4 tiles appear"
            all ->
                "All 4 tiles look good — tap Done to stitch"
            states[active] == QuadTileState.Blurry ->
                "Recapture $label — fill the frame and hold still"
            registrationFailed ->
                "Aim at the highlighted tile, then hold still"
            good == 0 ->
                "Move closer to $label and hold still"
            else ->
                "Next: $label ($good/4 done) — move there and hold still"
        }
        val guide = guideFor(active, all)
        val cells = states.map {
            when (it) {
                QuadTileState.Pending -> BandScanState.Empty
                QuadTileState.Blurry -> BandScanState.Soft
                QuadTileState.Good -> BandScanState.Locked
            }
        }
        return CoverageSnapshot(
            coveragePercent = (good * 100 / TILE_COUNT),
            qualityPercent = if (good == 0) 0 else ((good * 100) / TILE_COUNT),
            nextHint = hint,
            title = when {
                all -> "Ready"
                !pageMapped -> "Map the page"
                else -> "Tile ${active + 1} of 4"
            },
            readyToFinish = all,
            acceptedTiles = good,
            registrationFailed = registrationFailed,
            lastShotBlurry = lastShotBlurry,
            progressDeterminate = true,
            noPaper = noPaper,
            pullBack = pullBack,
            cells = cells,
            gridCols = 2,
            gridRows = 2,
            bands = cells,
            guideDirection = guide,
            tileStates = states.toList(),
            activeTileIndex = active,
            goodTileCount = good,
            pageMapped = pageMapped,
        )
    }

    private fun guideFor(tile: Int, all: Boolean): ScanGuideDirection {
        if (all) return ScanGuideDirection.None
        if (!pageMapped) return ScanGuideDirection.Hold
        return when (tile) {
            0 -> ScanGuideDirection.Hold
            1 -> ScanGuideDirection.Right
            2 -> ScanGuideDirection.Down
            3 -> ScanGuideDirection.Down // from TR: down+right; prefer down into BR
            else -> ScanGuideDirection.Hold
        }
    }

    private fun nextWorkIndex(): Int {
        for (i in states.indices) {
            if (states[i] == QuadTileState.Blurry) return i
        }
        for (i in states.indices) {
            if (states[i] == QuadTileState.Pending) return i
        }
        return 0
    }

    companion object {
        const val TILE_COUNT = 4
        const val BAND_COUNT = 4
        const val GRID_COLS = 2
        const val GRID_ROWS = 2
        const val SHARP_THRESHOLD = 40f
    }
}
