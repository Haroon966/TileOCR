package com.paperpanorama.ocr.capture

/**
 * Classifies whether each paper side sits inside the camera frame (interior)
 * or is cut off by the frame edge (clipped). JVM-testable — no OpenCV.
 *
 * Matches stitcher cut-off logic: margin ≈ 3% of the long edge.
 */
object PageEdgeClassifier {

    data class PaperRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(1f)
        val height: Float get() = (bottom - top).coerceAtLeast(1f)
        val area: Float get() = width * height
    }

    data class EdgeFlags(
        val topInterior: Boolean,
        val bottomInterior: Boolean,
        val leftInterior: Boolean,
        val rightInterior: Boolean,
    ) {
        val anyInterior: Boolean
            get() = topInterior || bottomInterior || leftInterior || rightInterior

        /** Paper fills (or nearly fills) the frame on the long axis — pull back. */
        fun fillsFrame(longAxisVertical: Boolean): Boolean =
            if (longAxisVertical) !topInterior && !bottomInterior
            else !leftInterior && !rightInterior

        fun longAxisEndsInterior(longAxisVertical: Boolean): Boolean =
            if (longAxisVertical) topInterior && bottomInterior
            else leftInterior && rightInterior
    }

    fun margin(imageW: Int, imageH: Int): Float =
        MARGIN_FRAC * maxOf(imageW, imageH)

    fun classify(rect: PaperRect, imageW: Int, imageH: Int): EdgeFlags {
        val m = margin(imageW, imageH)
        return EdgeFlags(
            topInterior = rect.top >= m,
            bottomInterior = rect.bottom <= imageH - m,
            leftInterior = rect.left >= m,
            rightInterior = rect.right <= imageW - m,
        )
    }

    fun fromBoundingBox(x: Int, y: Int, w: Int, h: Int): PaperRect =
        PaperRect(
            left = x.toFloat(),
            top = y.toFloat(),
            right = (x + w).toFloat(),
            bottom = (y + h).toFloat(),
        )

    fun isLongAxisVertical(rect: PaperRect): Boolean = rect.height >= rect.width

    /** Seed too tiny / corner-only. */
    fun isCredibleSeed(rect: PaperRect, imageW: Int, imageH: Int): Boolean {
        val frameArea = imageW.toFloat() * imageH.toFloat()
        if (frameArea <= 0f) return false
        if (rect.area < MIN_SEED_AREA_FRAC * frameArea) return false
        val flags = classify(rect, imageW, imageH)
        // Need at least one long-axis end interior OR both sides of short axis — not a tiny corner clip.
        val vertical = isLongAxisVertical(rect)
        val ends = if (vertical) {
            (if (flags.topInterior) 1 else 0) + (if (flags.bottomInterior) 1 else 0)
        } else {
            (if (flags.leftInterior) 1 else 0) + (if (flags.rightInterior) 1 else 0)
        }
        return ends >= 1 || flags.anyInterior
    }

    const val MARGIN_FRAC = 0.03f
    const val MIN_SEED_AREA_FRAC = 0.25f
}
