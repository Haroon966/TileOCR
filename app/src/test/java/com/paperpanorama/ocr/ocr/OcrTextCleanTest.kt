package com.paperpanorama.ocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextCleanTest {

    @Test
    fun stripMarkdown_removesHeadingsBoldLists() {
        val raw = """
            # Title
            **bold** and *italic*
            - item one
            1. item two
            `code`
        """.trimIndent()
        val out = OcrTextClean.stripMarkdown(raw)
        assertFalse(out.contains("#"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("`"))
        assertTrue(out.contains("Title"))
        assertTrue(out.contains("bold"))
        assertTrue(out.contains("item one"))
        assertTrue(out.contains("item two"))
        assertTrue(out.contains("code"))
    }

    @Test
    fun stripMarkdown_linksBecomeLabel() {
        assertEquals("Click here", OcrTextClean.stripMarkdown("[Click here](https://x.com)"))
    }

    @Test
    fun lines_skipsBlanks() {
        val lines = OcrTextClean.lines("a\n\n  \nb\n")
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun markdownFallbackBlocks_stacksParagraphs() {
        val blocks = OcrTextClean.markdownFallbackBlocks("Para one.\n\nPara two.\n\nPara three.")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].top < blocks[1].top)
        assertTrue(blocks[1].top < blocks[2].top)
        assertEquals("Para one.", blocks[0].text)
        assertTrue(blocks[0].left >= 0.05f)
        assertTrue(blocks[0].right <= 0.95f)
    }

    @Test
    fun linePack_evenYs_spreadsLines() {
        val ys = OcrLayoutMath.lineTops(boxTop = 100, boxHeight = 100, lineCount = 4, lineHeight = 20f)
        assertEquals(4, ys.size)
        assertEquals(100f, ys[0], 0.01f)
        // Spare 20px → 20/3 ≈ 6.67 between lines beyond lineHeight
        assertTrue(ys[1] > 120f)
        assertTrue(ys[3] + 20f <= 200.5f)
    }
}
