package com.paperpanorama.ocr.ocr

import org.json.JSONArray
import org.json.JSONObject

/** JVM-safe parsing for Google Cloud Vision `images:annotate` responses (no Android imports). */
object GoogleVisionJsonParse {

    /**
     * [imageW]/[imageH] are the pixel dimensions of the image bytes actually sent to the API —
     * Vision's `boundingPoly` vertices are absolute pixel coords in that same space.
     */
    fun parseResponse(raw: String, imageW: Int, imageH: Int): OcrPageResult? {
        val root = JSONObject(raw)
        val responses = root.optJSONArray("responses") ?: return null
        if (responses.length() == 0) return null
        val resp = responses.optJSONObject(0) ?: return null
        val annotations = resp.optJSONArray("textAnnotations")
        val fullText = annotations?.optJSONObject(0)?.optString("description", "").orEmpty()
        val blocks = parseWordBlocks(annotations)
        return OcrPageResult(
            markdown = fullText,
            blocks = blocks,
            pageWidth = imageW,
            pageHeight = imageH,
        )
    }

    private fun parseWordBlocks(annotations: JSONArray?): List<OcrBlock> {
        if (annotations == null || annotations.length() <= 1) return emptyList()
        val out = ArrayList<OcrBlock>(annotations.length() - 1)
        for (i in 1 until annotations.length()) {
            val a = annotations.optJSONObject(i) ?: continue
            val text = a.optString("description", "").trim()
            if (text.isEmpty()) continue
            val vertices = a.optJSONObject("boundingPoly")?.optJSONArray("vertices") ?: continue
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (j in 0 until vertices.length()) {
                val v = vertices.optJSONObject(j) ?: continue
                val x = v.optInt("x", 0).toFloat()
                val y = v.optInt("y", 0).toFloat()
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            if (minX > maxX || minY > maxY) continue
            out.add(OcrBlock("word", text, minX, minY, maxX, maxY))
        }
        return out
    }

    /** Null when [raw] carries no error — checks both request-level and per-image error shapes. */
    fun parseError(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val root = JSONObject(raw)
            val topLevel = root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            if (topLevel != null) return topLevel
            root.optJSONArray("responses")
                ?.optJSONObject(0)
                ?.optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            raw.take(200)
        }
    }
}
