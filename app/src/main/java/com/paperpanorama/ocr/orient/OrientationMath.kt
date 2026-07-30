package com.paperpanorama.ocr.orient

/**
 * Pure helpers for orientation scoring (JVM-testable without OpenCV).
 */
object OrientationMath {
    /** Score how “horizontal-line-like” a binary row projection is (higher = better). */
    fun projectionSharpness(rowSums: IntArray): Double {
        if (rowSums.isEmpty()) return 0.0
        val mean = rowSums.average()
        var variance = 0.0
        for (v in rowSums) {
            val d = v - mean
            variance += d * d
        }
        return variance / rowSums.size
    }

    /** Pick best of 0/90/180/270 given sharpness scores for each snap. */
    fun bestCardinal(scores: Map<Int, Double>): Int {
        return scores.maxByOrNull { it.value }?.key ?: 0
    }

    /** Clamp fine deskew to ±maxDegrees. */
    fun clampDeskew(degrees: Double, maxDegrees: Double = 15.0): Double {
        return degrees.coerceIn(-maxDegrees, maxDegrees)
    }

    /** Snap continuous angle to nearest 90°. */
    fun snapToCardinal(degrees: Double): Int {
        val norm = ((degrees % 360) + 360) % 360
        val candidates = listOf(0, 90, 180, 270)
        return candidates.minBy { kotlin.math.abs(it - norm).let { d -> minOf(d, 360 - d) } }
    }

    /**
     * CameraX [targetRotation] is usually [android.view.Surface] ROTATION_* (0..3).
     * Some call sites may already pass degrees. Normalize to 0/90/180/270.
     */
    fun displayRotationToDegrees(rotation: Int): Int = when (rotation) {
        0, 1, 2, 3 -> rotation * 90
        else -> {
            val n = ((rotation % 360) + 360) % 360
            when (n) {
                in 45 until 135 -> 90
                in 135 until 225 -> 180
                in 225 until 315 -> 270
                else -> 0
            }
        }
    }
}
