package com.paperpanorama.ocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleVisionJsonParseTest {

    private val sampleResponse = """
        {
          "responses": [
            {
              "textAnnotations": [
                { "description": "Hello world", "boundingPoly": {} },
                {
                  "description": "Hello",
                  "boundingPoly": {
                    "vertices": [
                      {"x": 10, "y": 20}, {"x": 60, "y": 20},
                      {"x": 60, "y": 40}, {"x": 10, "y": 40}
                    ]
                  }
                },
                {
                  "description": "world",
                  "boundingPoly": {
                    "vertices": [
                      {"x": 65, "y": 20}, {"x": 110, "y": 20},
                      {"x": 110, "y": 40}, {"x": 65, "y": 40}
                    ]
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parseResponse_extractsWordsWithPixelBoxes() {
        val page = GoogleVisionJsonParse.parseResponse(sampleResponse, imageW = 800, imageH = 600)
        assertNotNull(page)
        assertEquals("Hello world", page!!.markdown)
        assertEquals(800, page.pageWidth)
        assertEquals(600, page.pageHeight)
        assertEquals(2, page.blocks.size)

        val first = page.blocks[0]
        assertEquals("Hello", first.text)
        assertEquals(10f, first.left)
        assertEquals(20f, first.top)
        assertEquals(60f, first.right)
        assertEquals(40f, first.bottom)
    }

    @Test
    fun parseResponse_noTextAnnotations_returnsEmptyBlocks() {
        val page = GoogleVisionJsonParse.parseResponse(
            """{"responses":[{}]}""",
            imageW = 100,
            imageH = 200,
        )
        assertNotNull(page)
        assertEquals("", page!!.markdown)
        assertEquals(0, page.blocks.size)
    }

    @Test
    fun parseResponse_noResponses_returnsNull() {
        assertNull(GoogleVisionJsonParse.parseResponse("""{}""", 100, 100))
    }

    @Test
    fun parseError_topLevelError() {
        val raw = """{"error": {"code": 400, "message": "API key not valid"}}"""
        assertEquals("API key not valid", GoogleVisionJsonParse.parseError(raw))
    }

    @Test
    fun parseError_perImageError() {
        val raw = """{"responses":[{"error":{"code":3,"message":"Bad image data."}}]}"""
        assertEquals("Bad image data.", GoogleVisionJsonParse.parseError(raw))
    }

    @Test
    fun parseError_successResponse_returnsNull() {
        assertNull(GoogleVisionJsonParse.parseError(sampleResponse))
    }
}
