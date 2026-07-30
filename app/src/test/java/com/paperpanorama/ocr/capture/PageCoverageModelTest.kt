package com.paperpanorama.ocr.capture

import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CoverageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCoverageModelTest {

    private fun fullPage() = PageCoverageModel.PageRect(0f, 0f, 800f, 1200f)

    private fun cellRect(m: PageCoverageModel, row: Int, col: Int): PageCoverageModel.PageRect {
        val cw = (m.pageMaxX - m.pageMinX) / PageCoverageModel.GRID_COLS
        val rh = (m.pageMaxY - m.pageMinY) / PageCoverageModel.GRID_ROWS
        val x0 = m.pageMinX + col * cw + cw * 0.1f
        val x1 = m.pageMinX + (col + 1) * cw - cw * 0.1f
        val y0 = m.pageMinY + row * rh + rh * 0.1f
        val y1 = m.pageMinY + (row + 1) * rh - rh * 0.1f
        return PageCoverageModel.PageRect(x0, y0, x1, y1)
    }

    @Test
    fun startsUnmapped_notReady() {
        val m = PageCoverageModel()
        val s = m.snapshot()
        assertFalse(s.pageMapped)
        assertFalse(s.readyToFinish)
        assertEquals(0, s.lockedCellCount)
        assertEquals(CoverageSnapshot.TILE_COUNT, s.emptyCellCount)
    }

    @Test
    fun middleSeedNeverDone() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        // Paint only the middle of the page — not all cells.
        m.paintRect(PageCoverageModel.PageRect(200f, 400f, 600f, 800f), 80f)
        assertTrue(m.lockedCount() > 0)
        assertTrue(m.lockedCount() < CoverageSnapshot.TILE_COUNT)
        assertFalse(m.snapshot().readyToFinish)
    }

    @Test
    fun paintLocksCells_allLockedReady() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        m.paintRect(fullPage(), 80f)
        assertTrue(m.allLocked())
        assertTrue(m.snapshot().readyToFinish)
        assertEquals(CoverageSnapshot.TILE_COUNT, m.lockedCount())
        assertTrue(m.snapshot().nextHint.contains("Done", ignoreCase = true))
    }

    @Test
    fun softThenSharpImproves() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        val r = cellRect(m, 0, 0)
        m.paintRect(r, 20f) // Soft
        assertEquals(BandScanState.Soft, m.cellStates()[0])
        assertTrue(m.wouldImprove(r, 80f))
        m.paintRect(r, 80f)
        assertEquals(BandScanState.Locked, m.cellStates()[0])
    }

    @Test
    fun lockedOverlapAbove30_rejectsWithoutImprove() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        // Lock left half.
        m.paintRect(PageCoverageModel.PageRect(0f, 0f, 400f, 1200f), 80f)
        // Footprint mostly on locked half.
        val dup = PageCoverageModel.PageRect(0f, 0f, 350f, 1200f)
        val overlap = m.lockedOverlapFraction(dup)
        assertTrue(overlap > CoverageSnapshot.MAX_LOCKED_OVERLAP)
        assertFalse(m.shouldAccept(dup, 80f))
    }

    @Test
    fun softImproveAcceptsEvenHighOverlap() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        val softCell = cellRect(m, 0, 0)
        m.paintRect(softCell, 20f)
        // Lock surrounding area heavily.
        m.paintRect(PageCoverageModel.PageRect(100f, 0f, 800f, 1200f), 80f)
        // Footprint covering soft cell + lots of locked — still accept if soft improves.
        val footprint = PageCoverageModel.PageRect(0f, 0f, 500f, 200f)
        assertTrue(m.shouldAccept(footprint, 90f))
    }

    @Test
    fun lowOverlapAcceptsNewContent() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        m.paintRect(PageCoverageModel.PageRect(0f, 0f, 200f, 300f), 80f)
        val newArea = PageCoverageModel.PageRect(500f, 800f, 800f, 1200f)
        assertTrue(m.lockedOverlapFraction(newArea) <= CoverageSnapshot.MAX_LOCKED_OVERLAP)
        assertTrue(m.shouldAccept(newArea, 80f))
    }

    @Test
    fun nextTargetPrefersSoftThenEmpty() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        m.paintRect(cellRect(m, 0, 0), 20f) // soft first cell
        assertEquals(0, m.activeCellIndex())
        m.paintRect(cellRect(m, 0, 0), 80f)
        assertEquals(1, m.activeCellIndex())
    }

    @Test
    fun acceptedStillIncrements() {
        val m = PageCoverageModel()
        m.markPageMapped(0f, 0f, 800f, 1200f)
        m.onStillAccepted()
        m.onStillAccepted()
        assertEquals(2, m.snapshot().acceptedTiles)
    }
}
