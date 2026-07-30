package com.paperpanorama.ocr.ocr

/**
 * One Mistral OCR content block with pixel (or normalized) bounding box.
 * [left]/[top]/[right]/[bottom] are as returned; use [OcrLayoutMath.normalizeBox]
 * before drawing.
 */
data class OcrBlock(
    val type: String,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class OcrPageResult(
    val markdown: String,
    val blocks: List<OcrBlock>,
    /** Page dimensions from API when present; else 0. */
    val pageWidth: Int = 0,
    val pageHeight: Int = 0,
)
