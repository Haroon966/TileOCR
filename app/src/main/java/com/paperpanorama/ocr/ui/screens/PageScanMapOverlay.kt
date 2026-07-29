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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.LivePageHint
import com.paperpanorama.ocr.domain.NormQuad
import com.paperpanorama.ocr.domain.QuadTileState
import com.paperpanorama.ocr.domain.ScanGuideDirection
import com.paperpanorama.ocr.ui.theme.CameraChrome
import com.paperpanorama.ocr.ui.theme.DeepRichRed
import com.paperpanorama.ocr.ui.theme.SoftYellow

/**
 * AR-style live overlay: paper outline + 2×2 tiles, active highlight, status icons.
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

    val a11y =
        "Four tile scan. ${coverage.goodTileCount} of 4 good. " +
            CoverageSnapshot.tileLabel(coverage.activeTileIndex) + ". " +
            (live.hint ?: coverage.nextHint)

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = a11y },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val quads = live.tileQuads
            if (quads.size == 4) {
                quads.forEachIndexed { i, q ->
                    val state = coverage.tileStates.getOrElse(i) { QuadTileState.Pending }
                    val isActive = i == coverage.activeTileIndex
                    val fill = when {
                        state == QuadTileState.Good -> SoftYellow.copy(alpha = 0.22f)
                        state == QuadTileState.Blurry -> DeepRichRed.copy(alpha = 0.28f)
                        isActive -> SoftYellow.copy(alpha = 0.12f * pulseAlpha)
                        else -> Color.Black.copy(alpha = 0.28f)
                    }
                    val stroke = when {
                        state == QuadTileState.Good -> SoftYellow
                        state == QuadTileState.Blurry -> DeepRichRed
                        isActive -> SoftYellow.copy(alpha = pulseAlpha)
                        else -> SoftYellow.copy(alpha = 0.35f)
                    }
                    val path = quadPath(q, size.width, size.height)
                    drawPath(path, color = fill)
                    drawPath(
                        path,
                        color = stroke,
                        style = Stroke(width = if (isActive) 4.dp.toPx() else 2.dp.toPx()),
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
                drawPath(path, SoftYellow.copy(alpha = 0.7f), style = Stroke(3.dp.toPx()))
            }
        }

        // Tile status chips at quad centers when available.
        if (live.tileQuads.size == 4) {
            live.tileQuads.forEachIndexed { i, q ->
                val c = q.center()
                val state = coverage.tileStates.getOrElse(i) { QuadTileState.Pending }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(0.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    // Position via graphicsLayer fractions of parent — use BoxWithConstraints would be better;
                    // approximate with a full-size box and offset.
                }
            }
        }

        TileStatusRow(
            coverage = coverage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        )

        mosaic?.takeIf { !it.isRecycled }?.let { bmp ->
            Surface(
                color = CameraChrome.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 120.dp)
                    .border(1.dp, SoftYellow.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Text("4 tiles", style = MaterialTheme.typography.labelSmall, color = SoftYellow)
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Tile status mosaic",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(64.dp),
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
                LegendLine(SoftYellow, "Good")
                Spacer(modifier = Modifier.height(4.dp))
                LegendLine(DeepRichRed, "Blurry — recapture")
                Spacer(modifier = Modifier.height(4.dp))
                LegendLine(SoftYellow.copy(alpha = 0.35f), "Pending")
            }
        }
    }
}

@Composable
private fun TileStatusRow(coverage: CoverageSnapshot, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        coverage.tileStates.forEachIndexed { i, state ->
            val active = i == coverage.activeTileIndex
            Surface(
                color = when (state) {
                    QuadTileState.Good -> SoftYellow.copy(alpha = 0.25f)
                    QuadTileState.Blurry -> DeepRichRed.copy(alpha = 0.55f)
                    QuadTileState.Pending -> CameraChrome.copy(alpha = 0.75f)
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.border(
                    width = if (active) 2.dp else 1.dp,
                    color = if (active) SoftYellow else SoftYellow.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (state) {
                        QuadTileState.Good -> Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = SoftYellow,
                            modifier = Modifier.size(16.dp),
                        )
                        QuadTileState.Blurry -> Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = SoftYellow,
                            modifier = Modifier.size(16.dp),
                        )
                        QuadTileState.Pending -> Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SoftYellow,
                        )
                    }
                    Text(
                        text = when (i) {
                            0 -> "TL"
                            1 -> "TR"
                            2 -> "BL"
                            else -> "BR"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftYellow,
                    )
                }
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = SoftYellow.copy(alpha = 0.9f))
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
            Icon(icon, null, tint = SoftYellow, modifier = Modifier.size(48.dp))
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
