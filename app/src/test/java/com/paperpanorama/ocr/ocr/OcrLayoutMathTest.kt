package com.paperpanorama.ocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutMathTest {

    @Test
    fun normalizeBox_normalizedCoords() {
        val box = OcrLayoutMath.normalizeBox(0.1f, 0.2f, 0.5f, 0.4f, 1000, 2000)
        assertEquals(100, box.left)
        assertEquals(400, box.top)
        assertEquals(500, box.right)
        assertEquals(800, box.bottom)
    }

    @Test
    fun normalizeBox_absolutePixels() {
        val box = OcrLayoutMath.normalizeBox(10f, 20f, 110f, 60f, 800, 600)
        assertEquals(10, box.left)
        assertEquals(20, box.top)
        assertEquals(110, box.right)
        assertEquals(60, box.bottom)
    }

    @Test
    fun normalizeBox_scalesApiPageSize() {
        val box = OcrLayoutMath.normalizeBox(
            0f, 0f, 100f, 50f,
            pageW = 200, pageH = 100,
            apiPageW = 100, apiPageH = 50,
        )
        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(200, box.right)
        assertEquals(100, box.bottom)
    }

    @Test
    fun fitFontSize_picksLargestThatFits() {
        val size = OcrLayoutMath.fitFontSizePx(minPx = 8f, maxPx = 40f) { it <= 20.5f }
        assertTrue(size in 19.5f..20.5f)
    }

    @Test
    fun drawableAndTitleHelpers() {
        assertFalse(OcrLayoutMath.isDrawableTextBlock("image"))
        assertTrue(OcrLayoutMath.isDrawableTextBlock("text"))
        assertTrue(OcrLayoutMath.isTitleBlock("title"))
        assertFalse(OcrLayoutMath.isTitleBlock("text"))
    }
}
