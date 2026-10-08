package com.paperpanorama.ocr.ocr

import org.json.JSONArray
import org.json.JSONObject

/** JVM-safe OCR JSON parsing (no Android imports). */
object OcrJsonParse {

    fun parseResponse(raw: String): OcrPageResult? {
        val root = JSONObject(raw)
        val pages = root.optJSONArray("pages") ?: return null
        if (pages.length() == 0) return null
        val page = pages.getJSONObject(0)
        val markdown = page.optString("markdown", "")
        val dims = page.optJSONObject("dimensions")
        val pageW = dims?.optInt("width", 0)?.takeIf { it > 0 }
            ?: page.optInt("width", 0)
        val pageH = dims?.optInt("height", 0)?.takeIf { it > 0 }
            ?: page.optInt("height", 0)
        val blocks = parseBlocks(page.optJSONArray("blocks"))
        return OcrPageResult(
            markdown = markdown,
            blocks = blocks,
            pageWidth = pageW,
            pageHeight = pageH,
        )
    }

    fun parseBlocks(arr: JSONArray?): List<OcrBlock> {
        if (arr == null) return emptyList()
        val out = ArrayList<OcrBlock>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "text")
            val text = o.optString("content", o.optString("text", "")).trim()
            if (text.isEmpty()) continue
            val left = o.optDouble("top_left_x", o.optDouble("left", Double.NaN)).toFloat()
            val top = o.optDouble("top_left_y", o.optDouble("top", Double.NaN)).toFloat()
            val right = o.optDouble("bottom_right_x", o.optDouble("right", Double.NaN)).toFloat()
            val bottom = o.optDouble("bottom_right_y", o.optDouble("bottom", Double.NaN)).toFloat()
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                continue
            }
            out.add(OcrBlock(type, text, left, top, right, bottom))
        }
        return out
    }

    fun parseError(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            o.optJSONObject("error")?.optString("message")
                ?: o.optString("message").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            raw.take(200)
        }
    }
}
