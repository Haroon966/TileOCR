package com.paperpanorama.ocr.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Gallery-style page viewer: pinch zoom, pan when zoomed, double-tap toggle.
 * Resets when [resetKey] changes (e.g. pager page / URI).
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    doubleTapScale: Float = 2.75f,
    onScaleChanged: (Float) -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scaleAnim = remember { Animatable(1f) }
    val offsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(resetKey, bitmap) {
        scaleAnim.snapTo(1f)
        offsetAnim.snapTo(Offset.Zero)
        onScaleChanged(1f)
    }

    fun clampOffset(raw: Offset, scale: Float): Offset {
        if (scale <= 1.01f || containerSize == IntSize.Zero) return Offset.Zero
        val maxX = containerSize.width * (scale - 1f) / 2f
        val maxY = containerSize.height * (scale - 1f) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY),
        )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scope.launch {
            val nextScale = (scaleAnim.value * zoomChange).coerceIn(minScale, maxScale)
            scaleAnim.snapTo(nextScale)
            val nextOffset = if (nextScale > 1.01f) {
                clampOffset(offsetAnim.value + panChange, nextScale)
            } else {
                Offset.Zero
            }
            offsetAnim.snapTo(nextOffset)
            onScaleChanged(nextScale)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(resetKey, bitmap) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scaleAnim.value > 1.1f) {
                                scaleAnim.animateTo(1f, tween(220))
                                offsetAnim.animateTo(Offset.Zero, tween(220))
                                onScaleChanged(1f)
                            } else {
                                val target = doubleTapScale.coerceIn(minScale, maxScale)
                                // Pan so the tapped point moves toward center.
                                val centered = if (containerSize != IntSize.Zero) {
                                    val cx = containerSize.width / 2f
                                    val cy = containerSize.height / 2f
                                    clampOffset(
                                        Offset((cx - tap.x) * (target - 1f), (cy - tap.y) * (target - 1f)),
                                        target,
                                    )
                                } else {
                                    Offset.Zero
                                }
                                scaleAnim.animateTo(target, tween(220))
                                offsetAnim.animateTo(centered, tween(220))
                                onScaleChanged(target)
                            }
                        }
                    },
                )
            }
            .transformable(state = transformState),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetAnim.value.x
                    translationY = offsetAnim.value.y
                },
        )
    }
}

/** True when the viewer is zoomed enough that horizontal pager should not steal swipes. */
fun isGalleryZoomed(scale: Float): Boolean = scale > 1.05f

/** Extra decode budget so pinch-zoom stays sharp. */
fun galleryPreviewMaxSide(containerShortSide: Int): Int =
    max(1600, (containerShortSide * 2.5f).toInt().coerceAtMost(3200))
