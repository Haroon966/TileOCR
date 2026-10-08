package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperpanorama.ocr.ui.components.ZoomableImage
import com.paperpanorama.ocr.ui.theme.Canvas
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import com.paperpanorama.ocr.ui.theme.SurfaceMuted
import com.paperpanorama.ocr.util.BitmapDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OcrResultScreen(
    pageUri: Uri?,
    cleanUri: Uri?,
    markdown: String,
    isRunning: Boolean,
    status: String,
    error: String?,
    onCopy: () -> Unit,
    onSave: (isClean: Boolean) -> Unit,
    onExportPdf: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    pdfReady: Boolean = false,
) {
    var showClean by remember(cleanUri) { mutableStateOf(cleanUri != null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── App bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "Extracted Text",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // Spacer to balance the back button visually
            Spacer(modifier = Modifier.width(48.dp))
        }
        HorizontalDivider(color = Ink.copy(alpha = 0.08f), thickness = 1.dp)

        // ── Toggle chips ─────────────────────────────────────────────────────
        if (!isRunning && error == null && cleanUri != null) {
            SegmentedToggle(
                showClean = showClean,
                onSelect = { showClean = it },
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        // ── Image / loading / error area ─────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            when {
                isRunning -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Lime400,
                            trackColor = Lime400.copy(alpha = 0.18f),
                            strokeWidth = 5.dp,
                            modifier = Modifier.size(72.dp),
                        )
                        Text(
                            text = status.ifBlank { "Running OCR…" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.copy(alpha = 0.7f),
                        )
                    }
                }
                error != null -> {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFFF3F3),
                        shadowElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Lime400)
                                    .clickable(onClick = onRetry)
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                                    .semantics { contentDescription = "Retry" },
                            ) {
                                Text(
                                    text = "Try Again",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Ink,
                                )
                            }
                        }
                    }
                }
                else -> {
                    val activeUri = if (showClean) cleanUri else pageUri
                    if (activeUri != null) {
                        var bmp by remember(activeUri) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(activeUri) {
                            val path = activeUri.path ?: return@LaunchedEffect
                            bmp = withContext(Dispatchers.IO) {
                                BitmapDecode.decodeDownsampled(path, 2048)
                            }
                        }
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            val image = bmp
                            if (image != null) {
                                ZoomableImage(
                                    bitmap = image.asImageBitmap(),
                                    contentDescription = if (showClean) "Clean OCR page" else "Original scan",
                                    resetKey = activeUri to showClean,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = Lime400,
                                        trackColor = Lime400.copy(alpha = 0.18f),
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp,
                                    )
                                }
                            }
                        }

                        // Floating download button
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(14.dp)
                                .size(52.dp)
                                .shadow(6.dp, CircleShape)
                                .background(Lime400, CircleShape)
                                .clickable { onSave(showClean) }
                                .semantics { contentDescription = "Download image" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = Ink,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Markdown preview ─────────────────────────────────────────────────
        if (markdown.isNotBlank() && !isRunning && error == null) {
            Text(
                text = markdown.take(400) + if (markdown.length > 400) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(64.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        // ── Bottom action bar ─────────────────────────────────────────────────
        Surface(
            color = Ink,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ActionBarButton(
                        icon = Icons.Outlined.Refresh,
                        label = "Retry",
                        enabled = !isRunning,
                        onClick = onRetry,
                    )
                    ActionBarButton(
                        icon = Icons.Outlined.ContentCopy,
                        label = "Copy",
                        enabled = markdown.isNotBlank() && !isRunning,
                        onClick = onCopy,
                    )
                    ActionBarButton(
                        icon = Icons.Outlined.PictureAsPdf,
                        label = "PDF",
                        enabled = pdfReady && !isRunning,
                        tint = if (pdfReady && !isRunning) Lime400 else Color.White.copy(alpha = 0.3f),
                        onClick = onExportPdf,
                    )
                    val downloadEnabled = (if (showClean) cleanUri else pageUri) != null && !isRunning
                    ActionBarButton(
                        icon = Icons.Filled.Download,
                        label = "Download",
                        enabled = downloadEnabled,
                        tint = if (downloadEnabled) Color.White else Color.White.copy(alpha = 0.3f),
                        onClick = { onSave(showClean) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedToggle(
    showClean: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorOffset by animateDpAsState(
        targetValue = if (showClean) 0.dp else 0.dp,
        animationSpec = tween(200),
        label = "toggle",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50.dp))
            .background(SurfaceMuted)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        listOf(true to "Clean", false to "Original").forEach { (isClean, label) ->
            val selected = showClean == isClean
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(46.dp))
                    .background(if (selected) Lime400 else Color.Transparent)
                    .clickable { onSelect(isClean) }
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Ink else Ink.copy(alpha = 0.5f),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ActionBarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontSize = 11.sp,
        )
    }
}
