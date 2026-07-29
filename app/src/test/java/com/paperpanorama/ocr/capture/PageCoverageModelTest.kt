package com.paperpanorama.ocr.capture

import com.paperpanorama.ocr.domain.QuadTileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCoverageModelTest {

    @Test
    fun startsOnTileZero_notReady() {
        val m = PageCoverageModel()
        val s = m.snapshot()
        assertEquals(0, s.activeTileIndex)
        assertFalse(s.readyToFinish)
        assertEquals(0, s.goodTileCount)
        assertTrue(s.tileStates.all { it == QuadTileState.Pending })
    }

    @Test
    fun acceptFourTiles_readyForDone_notAuto() {
        val m = PageCoverageModel()
        repeat(4) {
            m.acceptTile(m.activeTileIndex())
        }
        val s = m.snapshot()
        assertTrue(s.readyToFinish)
        assertEquals(4, s.goodTileCount)
        assertTrue(s.nextHint.contains("Done", ignoreCase = true))
    }

    @Test
    fun blurryKeepsActiveForRecapture() {
        val m = PageCoverageModel()
        m.acceptTile(0)
        assertEquals(1, m.activeTileIndex())
        m.rejectBlurry(1)
        assertEquals(1, m.activeTileIndex())
        assertEquals(QuadTileState.Blurry, m.tileState(1))
        assertTrue(m.snapshot().nextHint.contains("blurry", ignoreCase = true) ||
            m.snapshot().nextHint.contains("Recapture", ignoreCase = true))
        m.acceptTile(1)
        assertEquals(QuadTileState.Good, m.tileState(1))
        assertEquals(2, m.activeTileIndex())
    }

    @Test
    fun coveragePercent_quarters() {
        val m = PageCoverageModel()
        m.acceptTile(0)
        assertEquals(25, m.snapshot().coveragePercent)
        m.acceptTile(1)
        assertEquals(50, m.snapshot().coveragePercent)
    }
}
