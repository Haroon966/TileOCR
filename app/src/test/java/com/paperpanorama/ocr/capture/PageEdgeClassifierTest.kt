package com.paperpanorama.ocr.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEdgeClassifierTest {

    @Test
    fun interiorMargins_marksAllEnds() {
        // 1000x1000 frame; paper inset by 50 (> 30 margin)
        val rect = PageEdgeClassifier.PaperRect(50f, 50f, 950f, 950f)
        val f = PageEdgeClassifier.classify(rect, 1000, 1000)
        assertTrue(f.topInterior)
        assertTrue(f.bottomInterior)
        assertTrue(f.leftInterior)
        assertTrue(f.rightInterior)
        assertTrue(f.longAxisEndsInterior(longAxisVertical = true))
        assertFalse(f.fillsFrame(longAxisVertical = true))
    }

    @Test
    fun clippedBottom_notInterior() {
        val rect = PageEdgeClassifier.PaperRect(100f, 100f, 900f, 1000f)
        val f = PageEdgeClassifier.classify(rect, 1000, 1000)
        assertTrue(f.topInterior)
        assertFalse(f.bottomInterior)
        assertFalse(f.longAxisEndsInterior(longAxisVertical = true))
    }

    @Test
    fun fillsFrame_bothLongEndsClipped() {
        val rect = PageEdgeClassifier.PaperRect(80f, 0f, 920f, 1000f)
        val f = PageEdgeClassifier.classify(rect, 1000, 1000)
        assertTrue(f.fillsFrame(longAxisVertical = true))
        assertFalse(f.topInterior)
        assertFalse(f.bottomInterior)
    }

    @Test
    fun tinyCornerSeed_notCredible() {
        val rect = PageEdgeClassifier.PaperRect(0f, 0f, 100f, 100f)
        assertFalse(PageEdgeClassifier.isCredibleSeed(rect, 1000, 1000))
    }

    @Test
    fun largeSeedWithOneEnd_credible() {
        val rect = PageEdgeClassifier.PaperRect(100f, 50f, 900f, 600f)
        assertTrue(PageEdgeClassifier.isCredibleSeed(rect, 1000, 1000))
    }

    @Test
    fun longAxisFromAspect() {
        val tall = PageEdgeClassifier.PaperRect(0f, 0f, 400f, 800f)
        val wide = PageEdgeClassifier.PaperRect(0f, 0f, 800f, 400f)
        assertTrue(PageEdgeClassifier.isLongAxisVertical(tall))
        assertFalse(PageEdgeClassifier.isLongAxisVertical(wide))
    }
}
