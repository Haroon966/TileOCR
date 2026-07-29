package com.paperpanorama.ocr.domain

import android.net.Uri

/** Document page corners in image pixel space, clockwise from top-left. */
data class DocQuad(
    val tlX: Float,
    val tlY: Float,
    val trX: Float,
    val trY: Float,
    val brX: Float,
    val brY: Float,
    val blX: Float,
    val blY: Float,
) {
    fun points(): List<Pair<Float, Float>> = listOf(
        tlX to tlY,
        trX to trY,
        brX to brY,
        blX to blY,
    )

    fun withPoint(index: Int, x: Float, y: Float): DocQuad = when (index) {
        0 -> copy(tlX = x, tlY = y)
        1 -> copy(trX = x, trY = y)
        2 -> copy(brX = x, brY = y)
        3 -> copy(blX = x, blY = y)
        else -> this
    }
}

enum class EnhancePreset {
    Original,
    Auto,
    Contrast,
    Bw,
}

data class PrepareResult(
    val pageUri: Uri,
    val width: Int,
    val height: Int,
)
