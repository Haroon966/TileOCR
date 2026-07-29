package com.paperpanorama.ocr.orient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationMathTest {
    @Test
    fun projectionSharpness_higherForPeakyRows() {
        val flat = IntArray(100) { 10 }
        val peaky = IntArray(100) { if (it % 10 == 0) 80 else 2 }
        assertTrue(
            OrientationMath.projectionSharpness(peaky) >
                OrientationMath.projectionSharpness(flat),
        )
    }

    @Test
    fun bestCardinal_picksMaxScore() {
        val scores = mapOf(0 to 1.0, 90 to 5.0, 180 to 2.0, 270 to 0.5)
        assertEquals(90, OrientationMath.bestCardinal(scores))
    }

    @Test
    fun clampDeskew_limitsRange() {
        assertEquals(15.0, OrientationMath.clampDeskew(40.0), 0.001)
        assertEquals(-15.0, OrientationMath.clampDeskew(-40.0), 0.001)
        assertEquals(3.5, OrientationMath.clampDeskew(3.5), 0.001)
    }

    @Test
    fun snapToCardinal_nearest() {
        assertEquals(0, OrientationMath.snapToCardinal(10.0))
        assertEquals(90, OrientationMath.snapToCardinal(80.0))
        assertEquals(180, OrientationMath.snapToCardinal(170.0))
        assertEquals(270, OrientationMath.snapToCardinal(260.0))
    }
}
