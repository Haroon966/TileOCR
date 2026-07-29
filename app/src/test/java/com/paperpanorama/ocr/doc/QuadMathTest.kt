package com.paperpanorama.ocr.doc

import com.paperpanorama.ocr.domain.DocQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadMathTest {
    @Test
    fun orderCorners_fromUnordered() {
        val raw = listOf(
            100f to 10f,
            10f to 10f,
            100f to 90f,
            10f to 90f,
        )
        val q = QuadMath.orderCorners(raw)
        assertEquals(10f, q.tlX, 0.01f)
        assertEquals(10f, q.tlY, 0.01f)
        assertEquals(100f, q.trX, 0.01f)
        assertEquals(10f, q.trY, 0.01f)
        assertEquals(100f, q.brX, 0.01f)
        assertEquals(90f, q.brY, 0.01f)
        assertEquals(10f, q.blX, 0.01f)
        assertEquals(90f, q.blY, 0.01f)
    }

    @Test
    fun expand_growsAroundCentroid() {
        val quad = DocQuad(10f, 10f, 110f, 10f, 110f, 60f, 10f, 60f)
        val grown = QuadMath.expand(quad, 0.1f)
        // centroid (60, 35); each corner moves 10% farther out
        assertEquals(5f, grown.tlX, 0.01f)
        assertEquals(7.5f, grown.tlY, 0.01f)
        assertEquals(115f, grown.brX, 0.01f)
        assertEquals(62.5f, grown.brY, 0.01f)
        assertTrue(QuadMath.area(grown) > QuadMath.area(quad))
    }

    @Test
    fun rotate90Cw_mapsCorners() {
        val quad = DocQuad(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f)
        val rotated = QuadMath.rotate90Cw(quad, 100, 50)
        // (0,0)→(50,0), (100,0)→(50,100), (100,50)→(0,100), (0,50)→(0,0)
        assertEquals(0f, rotated.tlX, 0.1f)
        assertEquals(0f, rotated.tlY, 0.1f)
        assertEquals(50f, rotated.trX, 0.1f)
        assertEquals(0f, rotated.trY, 0.1f)
        assertTrue(QuadMath.area(rotated) > 4000f)
    }

    @Test
    fun fullFrame_hasInset() {
        val q = QuadMath.fullFrame(1000, 2000, 0.02f)
        assertEquals(20f, q.tlX, 0.1f)
        assertEquals(40f, q.tlY, 0.1f)
        assertTrue(QuadMath.area(q) < 1000f * 2000f)
    }

    @Test
    fun sampleSize_halvesUntilFit() {
        assertEquals(1, com.paperpanorama.ocr.util.BitmapDecode.sampleSize(800, 600, 1000))
        assertEquals(2, com.paperpanorama.ocr.util.BitmapDecode.sampleSize(2000, 1000, 1000))
        assertEquals(8, com.paperpanorama.ocr.util.BitmapDecode.sampleSize(5000, 3000, 1200))
    }
}
