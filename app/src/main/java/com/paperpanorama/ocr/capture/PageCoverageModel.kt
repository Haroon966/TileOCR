package com.paperpanorama.ocr.capture

import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.QuadTileState
import com.paperpanorama.ocr.domain.ScanGuideDirection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Persistent page-space 8×12 sharpness grid (JVM-testable).
 *
 * Phase A: [markPageMapped] with page rect in seed coords.
 * Phase B: paint footprints; lock sharp cells; reject high locked-overlap duplicates.
 */
class PageCoverageModel(
    private val cols: Int = GRID_COLS,
    private val rows: Int = GRID_ROWS,
    private val sharpThreshold: Float = SHARP_THRESHOLD,
    private val maxLockedOverlap: Float = CoverageSnapshot.MAX_LOCKED_OVERLAP,
) {
    private val cellCount = cols * rows
    private val sharpness = FloatArray(cellCount) { UNCOVERED }

    var pageMinX: Float = 0f
        private set
    var pageMaxX: Float = 1f
        private set
    var pageMinY: Float = 0f
        private set
    var pageMaxY: Float = 1f
        private set

    private var pageMapped = false
    private var acceptedStills = 0
    private var lastShotBlurry = false
    private var lastShotDuplicate = false
    private var noPaper = false
    private var pullBack = false
    private var registrationFailed = false

    fun reset() {
        sharpness.fill(UNCOVERED)
        pageMinX = 0f
        pageMaxX = 1f
        pageMinY = 0f
        pageMaxY = 1f
        pageMapped = false
        acceptedStills = 0
        lastShotBlurry = false
        lastShotDuplicate = false
        noPaper = false
        pullBack = false
        registrationFailed = false
    }

    fun isPageMapped(): Boolean = pageMapped

    fun markPageMapped(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        pageMinX = minX
        pageMinY = minY
        pageMaxX = max(maxX, minX + 1f)
        pageMaxY = max(maxY, minY + 1f)
        pageMapped = true
        pullBack = false
        noPaper = false
    }

    fun markPageMapped() {
        pageMapped = true
        pullBack = false
        noPaper = false
    }

    fun markNoPaper() {
        noPaper = true
        pullBack = false
        lastShotBlurry = false
        lastShotDuplicate = false
    }

    fun markPullBack() {
        pullBack = true
        noPaper = false
        lastShotBlurry = false
        lastShotDuplicate = false
    }

    fun markBlurry() {
        lastShotBlurry = true
        lastShotDuplicate = false
        registrationFailed = false
    }

    fun markDuplicate() {
        lastShotDuplicate = true
        lastShotBlurry = false
        registrationFailed = false
    }

    fun markRegistrationFailed() {
        registrationFailed = true
        lastShotBlurry = false
        lastShotDuplicate = false
    }

    fun onStillAccepted() {
        acceptedStills++
        lastShotBlurry = false
        lastShotDuplicate = false
        noPaper = false
        pullBack = false
        registrationFailed = false
    }

    data class PageRect(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        fun area(): Float = (maxX - minX).coerceAtLeast(0f) * (maxY - minY).coerceAtLeast(0f)
    }

    /** Fraction of [rect] area that falls on already-Locked cells (0..1). */
    fun lockedOverlapFraction(rect: PageRect): Float {
        val cells = intersectingCells(rect) ?: return 0f
        if (cells.isEmpty()) return 0f
        val locked = cells.count { sharpness[it] >= sharpThreshold }
        return locked.toFloat() / cells.size
    }

    fun wouldImprove(rect: PageRect, sharp: Float): Boolean {
        val cells = intersectingCells(rect) ?: return false
        for (i in cells) {
            val cur = sharpness[i]
            if (cur < 0f) return true
            if (sharp > cur + IMPROVE_EPS) return true
        }
        return false
    }

    /**
     * Decide whether to accept a footprint.
     * Reject if locked overlap > 30% AND no Soft/Empty improvement.
     */
    fun shouldAccept(rect: PageRect, sharp: Float): Boolean {
        if (!pageMapped) return false
        if (rect.area() <= 0f) return false
        val overlap = lockedOverlapFraction(rect)
        val improves = wouldImprove(rect, sharp)
        if (overlap > maxLockedOverlap && !improves) return false
        // Improving Soft under high overlap is allowed; pure duplicate Locked is not.
        if (overlap > maxLockedOverlap) {
            // Only Soft improvements (not empty expansion under heavy lock) when overlap high:
            val cells = intersectingCells(rect) ?: return false
            val softImprove = cells.any {
                val cur = sharpness[it]
                cur >= 0f && cur < sharpThreshold && sharp > cur + IMPROVE_EPS
            }
            return softImprove
        }
        return true
    }

    fun paintRect(rect: PageRect, sharp: Float) {
        if (!pageMapped || sharp < 0f || rect.area() <= 0f) return
        val cells = intersectingCells(rect) ?: return
        for (i in cells) {
            sharpness[i] = max(sharpness[i], sharp)
        }
    }

    fun cellStates(): List<BandScanState> = sharpness.map { scoreToState(it) }

    fun activeCellIndex(): Int {
        nextTarget()?.let { (r, c) -> return r * cols + c }
        return 0
    }

    fun allLocked(): Boolean =
        pageMapped && sharpness.all { it >= sharpThreshold }

    fun lockedCount(): Int = sharpness.count { it >= sharpThreshold }

    fun softCount(): Int = sharpness.count { it >= 0f && it < sharpThreshold }

    fun emptyCount(): Int = sharpness.count { it < 0f }

    fun snapshot(): CoverageSnapshot {
        val locked = lockedCount()
        val soft = softCount()
        val empty = emptyCount()
        // Full grid lock OR enough locked cells + stills — user may Done anytime in UI too.
        val ready = allLocked() || (pageMapped && lockedCount() >= (cellCount + 1) / 2 && acceptedStills >= 2)
        val target = nextTarget()
        val active = activeCellIndex()
        val cells = cellStates()
        val hint = when {
            lastShotBlurry -> "Blurry — hold still closer to the red region and recapture"
            lastShotDuplicate -> "Already scanned — follow the arrow to a red / missing region"
            noPaper -> "Can't see paper — change background or lighting"
            pullBack && !pageMapped -> "Pull back so the whole page shows with margins"
            pullBack -> "Ease back a little — keep page edges in view"
            !pageMapped -> "Frame the whole page so the 8×12 grid can lock on"
            ready -> "All tiles sharp — tap Done to stitch"
            soft > 0 -> "Move closer to the red (soft) region and hold still"
            else -> "Move closer to the missing region and hold still ($locked/$cellCount)"
        }
        val title = when {
            ready -> "Ready"
            !pageMapped -> "Map the page"
            else -> "Scan page"
        }
        val (tLong, tCross) = target?.let {
            ((it.first + 0.5f) / rows) to ((it.second + 0.5f) / cols)
        } ?: (null to null)

        return CoverageSnapshot(
            coveragePercent = if (!pageMapped) 0 else ((locked * 100f) / cellCount).toInt(),
            qualityPercent = if (locked + soft == 0) 0 else ((locked * 100f) / (locked + soft)).toInt(),
            nextHint = hint,
            title = title,
            readyToFinish = ready,
            acceptedTiles = acceptedStills,
            registrationFailed = registrationFailed,
            lastShotBlurry = lastShotBlurry,
            progressDeterminate = pageMapped,
            noPaper = noPaper,
            pullBack = pullBack,
            cells = cells,
            gridCols = cols,
            gridRows = rows,
            bands = cells,
            nextTargetNorm = tLong,
            nextTargetCrossNorm = tCross,
            guideDirection = guideFor(target, ready),
            lastShotDuplicate = lastShotDuplicate,
            tileStates = cells.map {
                when (it) {
                    BandScanState.Empty -> QuadTileState.Pending
                    BandScanState.Soft -> QuadTileState.Blurry
                    BandScanState.Locked -> QuadTileState.Good
                }
            },
            activeTileIndex = active,
            goodTileCount = locked,
            pageMapped = pageMapped,
            lockedCellCount = locked,
            softCellCount = soft,
            emptyCellCount = empty,
        )
    }

    private fun scoreToState(score: Float): BandScanState = when {
        score < 0f -> BandScanState.Empty
        score < sharpThreshold -> BandScanState.Soft
        else -> BandScanState.Locked
    }

    private fun nextTarget(): Pair<Int, Int>? {
        if (!pageMapped) return null
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val s = sharpness[r * cols + c]
                if (s >= 0f && s < sharpThreshold) return r to c
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (sharpness[r * cols + c] < 0f) return r to c
            }
        }
        return null
    }

    private fun guideFor(target: Pair<Int, Int>?, ready: Boolean): ScanGuideDirection {
        if (ready) return ScanGuideDirection.None
        if (!pageMapped || target == null) return ScanGuideDirection.Hold
        val (r, c) = target
        val cy = (rows - 1) / 2f
        val cx = (cols - 1) / 2f
        val dr = r - cy
        val dc = c - cx
        return if (kotlin.math.abs(dr) >= kotlin.math.abs(dc)) {
            if (dr < 0) ScanGuideDirection.Up else ScanGuideDirection.Down
        } else {
            if (dc < 0) ScanGuideDirection.Left else ScanGuideDirection.Right
        }
    }

    private fun intersectingCells(rect: PageRect): List<Int>? {
        val spanX = pageMaxX - pageMinX
        val spanY = pageMaxY - pageMinY
        if (spanX <= 0f || spanY <= 0f) return null
        val x0 = ((rect.minX - pageMinX) / spanX).coerceIn(0f, 1f)
        val x1 = ((rect.maxX - pageMinX) / spanX).coerceIn(0f, 1f)
        val y0 = ((rect.minY - pageMinY) / spanY).coerceIn(0f, 1f)
        val y1 = ((rect.maxY - pageMinY) / spanY).coerceIn(0f, 1f)
        if (x1 <= x0 || y1 <= y0) return null
        val c0 = floor(x0 * cols).toInt().coerceIn(0, cols - 1)
        val c1 = ceil(x1 * cols).toInt().coerceIn(1, cols)
        val r0 = floor(y0 * rows).toInt().coerceIn(0, rows - 1)
        val r1 = ceil(y1 * rows).toInt().coerceIn(1, rows)
        val out = ArrayList<Int>((r1 - r0) * (c1 - c0))
        for (r in r0 until r1) {
            for (c in c0 until c1) {
                out.add(r * cols + c)
            }
        }
        return out
    }

    companion object {
        const val GRID_COLS = CoverageSnapshot.GRID_COLS
        const val GRID_ROWS = CoverageSnapshot.GRID_ROWS
        const val BAND_COUNT = GRID_COLS * GRID_ROWS
        const val SHARP_THRESHOLD = 40f
        const val UNCOVERED = -1f
        const val IMPROVE_EPS = 8f
    }
}
