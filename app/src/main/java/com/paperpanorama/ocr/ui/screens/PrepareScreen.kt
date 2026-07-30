package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.domain.DocQuad
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.util.BitmapDecode
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val PREVIEW_LONG_EDGE = 1600

@Composable
fun PrepareScreen(
    mosaicUri: Uri?,
    usedFallback: Boolean,
    quad: DocQuad?,
    enhancePreset: EnhancePreset,
    isDetectingQuad: Boolean,
    isPreparingPage: Boolean,
    prepareError: String?,
    onCornerMove: (index: Int, x: Float, y: Float) -> Unit,
    onResetQuad: () -> Unit,
    onEnhanceChange: (EnhancePreset) -> Unit,
    onRotate90: () -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var fullW by remember { mutableStateOf(0) }
    var fullH by remember { mutableStateOf(0) }

    LaunchedEffect(mosaicUri) {
        val old = preview
        preview = null
        old?.recycle()
        val path = mosaicUri?.path ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bounds = BitmapDecode.bounds(path)
            fullW = bounds?.first ?: 0
            fullH = bounds?.second ?: 0
            preview = BitmapDecode.decodeDownsampled(path, PREVIEW_LONG_EDGE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Ink)
            }
            Text("Prepare page", style = MaterialTheme.typography.titleLarge, color = Ink)
            Row {
                IconButton(
                    onClick = onRotate90,
                    modifier = Modifier.semantics { contentDescription = "Rotate 90 degrees" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, tint = Ink)
                }
                IconButton(
                    onClick = onResetQuad,
                    modifier = Modifier.semantics { contentDescription = "Reset corners" },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Ink)
                }
                TextButton(onClick = onRetake) { Text("Retake", color = Ink) }
            }
        }

        if (usedFallback) {
            Text(
                text = "Best available mosaic — adjust corners if needed.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = Ink.copy(alpha = 0.7f),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null && fullW > 0 && fullH > 0) {
                CornerEditor(
                    bitmap = bmp,
                    fullWidth = fullW,
                    fullHeight = fullH,
                    quad = quad,
                    onCornerMove = onCornerMove,
                )
            } else {
                CircularProgressIndicator(color = Lime400)
            }
            if (isDetectingQuad) {
                Text(
                    text = "Finding page…",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            text = "Enhance",
            style = MaterialTheme.typography.labelLarge,
            color = Lime400,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnhancePreset.entries.forEach { preset ->
                FilterChip(
                    selected = enhancePreset == preset,
                    onClick = { onEnhanceChange(preset) },
                    label = {
                        Text(
                            when (preset) {
                                EnhancePreset.Original -> "Original"
                                EnhancePreset.Auto -> "Auto"
                                EnhancePreset.Contrast -> "Contrast"
                                EnhancePreset.Bw -> "B&W"
                            },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Lime400,
                        selectedLabelColor = Ink,
                    ),
                )
            }
        }

        if (prepareError != null) {
            Text(
                text = prepareError,
                color = Lime400,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Button(
            onClick = onConfirm,
            enabled = !isPreparingPage && quad != null && mosaicUri != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp)
                .semantics { contentDescription = "Save page" },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lime400,
                contentColor = Ink,
            ),
        ) {
            if (isPreparingPage) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Ink,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Save page", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CornerEditor(
    bitmap: Bitmap,
    fullWidth: Int,
    fullHeight: Int,
    quad: DocQuad?,
    onCornerMove: (index: Int, x: Float, y: Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / fullWidth, boxH / fullHeight)
        val originX = (boxW - fullWidth * scale) / 2f
        val originY = (boxH - fullHeight * scale) / 2f
        val density = LocalDensity.current
        val handlePx = with(density) { 28.dp.toPx() }
        val loupeDiameter = with(density) { 120.dp.toPx() }
        val loupePad = with(density) { 12.dp.toPx() }
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        var activeCorner by remember { mutableStateOf<Int?>(null) }
        var loupeX by remember { mutableFloatStateOf(0f) }
        var loupeY by remember { mutableFloatStateOf(0f) }

        fun toView(x: Float, y: Float) = Offset(originX + x * scale, originY + y * scale)

        Image(
            bitmap = imageBitmap,
            contentDescription = "Stitched page",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        if (quad != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pts = quad.points().map { (x, y) -> toView(x, y) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                drawPath(path, color = Lime400.copy(alpha = 0.22f))
                drawPath(path, color = Lime400, style = Stroke(width = 3f))
            }

            quad.points().forEachIndexed { index, (ix, iy) ->
                var dragX by remember(index, quad) { mutableFloatStateOf(ix) }
                var dragY by remember(index, quad) { mutableFloatStateOf(iy) }
                LaunchedEffect(ix, iy) {
                    dragX = ix
                    dragY = iy
                }
                val live = toView(dragX, dragY)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (live.x - handlePx / 2).roundToInt(),
                                (live.y - handlePx / 2).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .background(Lime400, CircleShape)
                        .semantics { contentDescription = "Corner ${index + 1}" }
                        .pointerInput(scale, originX, originY, fullWidth, fullHeight, index) {
                            detectDragGestures(
                                onDragStart = {
                                    activeCorner = index
                                    loupeX = dragX
                                    loupeY = dragY
                                },
                                onDragEnd = { activeCorner = null },
                                onDragCancel = { activeCorner = null },
                            ) { change, dragAmount ->
                                change.consume()
                                dragX = (dragX + dragAmount.x / scale).coerceIn(0f, fullWidth - 1f)
                                dragY = (dragY + dragAmount.y / scale).coerceIn(0f, fullHeight - 1f)
                                loupeX = dragX
                                loupeY = dragY
                                onCornerMove(index, dragX, dragY)
                            }
                        },
                )
            }
        }

        if (activeCorner != null) {
            CropLoupe(
                imageBitmap = imageBitmap,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                focusX = loupeX,
                focusY = loupeY,
                viewScale = scale,
                diameterPx = loupeDiameter,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = with(density) { loupePad.toDp() }, end = with(density) { loupePad.toDp() })
                    .size(with(density) { loupeDiameter.toDp() })
                    .semantics { contentDescription = "Zoomed crop point" },
            )
        }
    }
}

/** Fixed top-right magnifier: zooms the active corner for precise placement. */
@Composable
private fun CropLoupe(
    imageBitmap: ImageBitmap,
    bitmapWidth: Int,
    bitmapHeight: Int,
    fullWidth: Int,
    fullHeight: Int,
    focusX: Float,
    focusY: Float,
    viewScale: Float,
    diameterPx: Float,
    modifier: Modifier = Modifier,
) {
    val zoom = 2.75f
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val circle = Path().apply {
            addOval(
                Rect(
                    left = cx - r,
                    top = cy - r,
                    right = cx + r,
                    bottom = cy + r,
                ),
            )
        }

        // Region of the full image shown inside the loupe, in full-image pixels.
        val halfFull = (diameterPx / 2f) / (viewScale * zoom).coerceAtLeast(0.01f)
        val bx = focusX * bitmapWidth / fullWidth
        val by = focusY * bitmapHeight / fullHeight
        val halfBmpX = halfFull * bitmapWidth / fullWidth
        val halfBmpY = halfFull * bitmapHeight / fullHeight
        val srcLeft = (bx - halfBmpX).roundToInt().coerceIn(0, (bitmapWidth - 1).coerceAtLeast(0))
        val srcTop = (by - halfBmpY).roundToInt().coerceIn(0, (bitmapHeight - 1).coerceAtLeast(0))
        val srcRight = (bx + halfBmpX).roundToInt().coerceIn(srcLeft + 1, bitmapWidth)
        val srcBottom = (by + halfBmpY).roundToInt().coerceIn(srcTop + 1, bitmapHeight)
        val srcW = srcRight - srcLeft
        val srcH = srcBottom - srcTop

        clipPath(circle) {
            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.08f),
                radius = r,
                center = Offset(cx, cy),
            )
        }

        drawCircle(
            color = Ink,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Lime400,
            radius = r - 2.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )

        val cross = 14.dp.toPx()
        val stroke = 2.dp.toPx()
        drawLine(Lime400, Offset(cx - cross, cy), Offset(cx + cross, cy), strokeWidth = stroke)
        drawLine(Lime400, Offset(cx, cy - cross), Offset(cx, cy + cross), strokeWidth = stroke)
        drawCircle(color = Ink, radius = 3.dp.toPx(), center = Offset(cx, cy))
        drawCircle(
            color = Lime400,
            radius = 3.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
