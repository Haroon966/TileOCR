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

    @Test
    fun renderSize_prefersLarger() {
        assertEquals(2000 to 3000, OcrLayoutMath.renderSize(1000, 1500, 2000, 3000))
        assertEquals(1200 to 1600, OcrLayoutMath.renderSize(1200, 1600, 800, 900))
    }

    @Test
    fun textStretchScaleX_identityAndEmpty() {
        assertEquals(1f, OcrLayoutMath.textStretchScaleX(100f, 100f), 0.001f)
        assertEquals(2f, OcrLayoutMath.textStretchScaleX(200f, 100f), 0.001f)
        assertEquals(1f, OcrLayoutMath.textStretchScaleX(100f, 0f), 0.001f)
        assertEquals(1f, OcrLayoutMath.textStretchScaleX(100f, -5f), 0.001f)
    }

    @Test
    fun textFitScaleX_neverExpands() {
        assertEquals(1f, OcrLayoutMath.textFitScaleX(200f, 100f), 0.001f)
        assertEquals(0.5f, OcrLayoutMath.textFitScaleX(50f, 100f), 0.001f)
    }

    @Test
    fun fitPackedFontSize_respectsHeightBudget() {
        val size = OcrLayoutMath.fitPackedFontSizePx(
            boxWidth = 500f,
            boxHeight = 100f,
            lineCount = 4,
            measureWidth = { it * 2f }, // always fits width for small sizes
            lineHeight = { it },
        )
        // 4 * size <= 100 → size <= 25
        assertTrue(size in 20f..25.5f)
    }

    @Test
    fun fitPackedFontSize_singleLineUsesFullBandHeight() {
        val size = OcrLayoutMath.fitPackedFontSizePx(
            boxWidth = 400f,
            boxHeight = 40f,
            lineCount = 1,
            measureWidth = { it * 3f },
            lineHeight = { it },
        )
        assertTrue(size in 35f..40.5f)
    }

    @Test
    fun stretchText_keepsNewlinesCollapsesSpaces() {
        val out = OcrLayoutMath.stretchText("  hello   world  \n\n  next   line  ")
        assertEquals("hello world\n\nnext line", out)
        assertEquals(listOf("hello world", "next line"), OcrLayoutMath.stretchLines(out))
    }

    @Test
    fun equalLineBands_splitsBoxEvenly() {
        val box = OcrLayoutMath.PixelBox(10, 0, 110, 90)
        val bands = OcrLayoutMath.equalLineBands(box, 3)
        assertEquals(3, bands.size)
        assertEquals(0, bands[0].top)
        assertEquals(30, bands[0].bottom)
        assertEquals(30, bands[1].top)
        assertEquals(60, bands[1].bottom)
        assertEquals(60, bands[2].top)
        assertEquals(90, bands[2].bottom)
        assertEquals(10, bands[0].left)
        assertEquals(110, bands[0].right)
    }

    @Test
    fun expandMultilineToLineBands_splitsParentBox() {
        val block = OcrBlock(
            type = "text",
            text = "Alpha\nBeta\nGamma",
            left = 0f,
            top = 0f,
            right = 100f,
            bottom = 90f,
        )
        val lines = OcrLayoutMath.expandMultilineToLineBlocks(listOf(block))
        assertEquals(3, lines.size)
        assertEquals(OcrLayoutMath.LINE_TYPE, lines[0].type)
        assertEquals("Alpha", lines[0].text)
        assertEquals(0f, lines[0].top, 0.001f)
        assertEquals(30f, lines[0].bottom, 0.001f)
        assertEquals("Gamma", lines[2].text)
        assertEquals(60f, lines[2].top, 0.001f)
        assertEquals(90f, lines[2].bottom, 0.001f)
        // Vision words untouched
        val word = OcrBlock(OcrLayoutMath.WORD_TYPE, "Hi", 1f, 2f, 3f, 4f)
        assertEquals(listOf(word), OcrLayoutMath.expandMultilineToLineBlocks(listOf(word)))
    }
}
