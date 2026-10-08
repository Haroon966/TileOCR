package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.LivePageHint
import com.paperpanorama.ocr.domain.NormQuad
import com.paperpanorama.ocr.domain.ScanGuideDirection
import com.paperpanorama.ocr.ui.theme.CameraChrome
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400

/**
 * Persistent 8×12 page-space overlay: Locked = soft yellow fill; Soft/Empty = red.
 */
@Composable
fun PageScanMapOverlay(
    coverage: CoverageSnapshot,
    live: LivePageHint,
    mosaic: Bitmap?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "tilePulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (reduceMotion) 0 else 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val arrowBob by pulse.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (reduceMotion) 0 else 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "arrowBob",
    )

    val total = coverage.gridCols * coverage.gridRows
    val a11y =
        "Page grid ${coverage.gridCols} by ${coverage.gridRows}. " +
            "${coverage.lockedCellCount} of $total locked. " +
            CoverageSnapshot.tileLabel(coverage.activeTileIndex) + ". " +
            (live.hint ?: coverage.nextHint)

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = a11y },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val quads = live.tileQuads
            val cells = coverage.cells
            if (quads.isNotEmpty()) {
                quads.forEachIndexed { i, q ->
                    val state = cells.getOrElse(i) { BandScanState.Empty }
                    val isActive = i == coverage.activeTileIndex
                    val fill = when {
                        state == BandScanState.Locked -> Lime400.copy(alpha = 0.22f)
                        state == BandScanState.Soft -> Ink.copy(alpha = 0.28f)
                        isActive -> Lime400.copy(alpha = 0.10f * pulseAlpha)
                        else -> Ink.copy(alpha = 0.12f)
                    }
                    val stroke = when {
                        state == BandScanState.Locked -> Lime400
                        state == BandScanState.Soft -> Ink
                        isActive -> Lime400.copy(alpha = pulseAlpha)
                        else -> Ink.copy(alpha = 0.55f)
                    }
                    val path = quadPath(q, size.width, size.height)
                    drawPath(path, color = fill)
                    drawPath(
                        path,
                        color = stroke,
                        style = Stroke(width = if (isActive) 2.5.dp.toPx() else 1.dp.toPx()),
                    )
                }
            } else if (live.paperQuad.size == 4) {
                val path = Path().apply {
                    val q = live.paperQuad
                    moveTo(q[0].x * size.width, q[0].y * size.height)
                    lineTo(q[1].x * size.width, q[1].y * size.height)
                    lineTo(q[2].x * size.width, q[2].y * size.height)
                    lineTo(q[3].x * size.width, q[3].y * size.height)
                    close()
                }
                drawPath(path, Lime400.copy(alpha = 0.7f), style = Stroke(3.dp.toPx()))
            }
        }

        Surface(
            color = CameraChrome.copy(alpha = 0.85f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        ) {
            Text(
                text = "${coverage.lockedCellCount}/$total locked" +
                    if (coverage.softCellCount > 0) " · ${coverage.softCellCount} soft" else "",
                style = MaterialTheme.typography.labelMedium,
                color = Lime400,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        mosaic?.takeIf { !it.isRecycled }?.let { bmp ->
            Surface(
                color = CameraChrome.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 120.dp)
                    .border(1.dp, Lime400.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Text("8×12", style = MaterialTheme.typography.labelSmall, color = Lime400)
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Cell coverage mosaic",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(64.dp, 96.dp),
                    )
                }
            }
        }

        if (!coverage.readyToFinish) {
            GuideArrow(
                direction = coverage.guideDirection,
                bob = arrowBob,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Surface(
            color = CameraChrome.copy(alpha = 0.8f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 156.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                LegendLine(Lime400, "Locked (sharp)")
                Spacer(modifier = Modifier.height(4.dp))
                LegendLine(Ink, "Soft / missing — zoom in")
                Spacer(modifier = Modifier.height(4.dp))
                LegendLine(Lime400.copy(alpha = 0.45f), "Active target")
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawRect(color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lime400.copy(alpha = 0.9f))
    }
}

@Composable
private fun GuideArrow(
    direction: ScanGuideDirection,
    bob: Float,
    modifier: Modifier = Modifier,
) {
    if (direction == ScanGuideDirection.None || direction == ScanGuideDirection.Hold) return
    val icon = when (direction) {
        ScanGuideDirection.Up -> Icons.Filled.KeyboardArrowUp
        ScanGuideDirection.Down -> Icons.Filled.KeyboardArrowDown
        ScanGuideDirection.Left -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        ScanGuideDirection.Right -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        else -> return
    }
    val (dx, dy) = when (direction) {
        ScanGuideDirection.Up -> 0f to -bob
        ScanGuideDirection.Down -> 0f to bob
        ScanGuideDirection.Left -> -bob to 0f
        ScanGuideDirection.Right -> bob to 0f
        else -> 0f to 0f
    }
    Surface(
        color = CameraChrome.copy(alpha = 0.72f),
        shape = RoundedCornerShape(40.dp),
        modifier = modifier
            .graphicsLayer { translationX = dx; translationY = dy }
            .size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, null, tint = Lime400, modifier = Modifier.size(48.dp))
        }
    }
}

private fun quadPath(q: NormQuad, w: Float, h: Float): Path = Path().apply {
    moveTo(q.tl.x * w, q.tl.y * h)
    lineTo(q.tr.x * w, q.tr.y * h)
    lineTo(q.br.x * w, q.br.y * h)
    lineTo(q.bl.x * w, q.bl.y * h)
    close()
}
