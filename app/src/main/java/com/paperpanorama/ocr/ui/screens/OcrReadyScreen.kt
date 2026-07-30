package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.ui.components.ZoomableImage
import com.paperpanorama.ocr.ui.components.galleryPreviewMaxSide
import com.paperpanorama.ocr.ui.components.isGalleryZoomed
import com.paperpanorama.ocr.util.BitmapDecode
import com.paperpanorama.ocr.ui.theme.CameraChrome
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Opened-image viewer (reference layout): full image + bottom tool sheet
 * (Auto / Crop / Rotate / Rescan / Delete), Cancel | Done, swipe between pages.
 */
@Composable
fun OcrReadyScreen(
    pageUri: Uri?,
    pageWidth: Int,
    pageHeight: Int,
    library: List<SavedScan>,
    libraryScanId: String?,
    toolsEnabled: Boolean = true,
    onSelectScan: (String) -> Unit,
    onAutoEnhance: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onRetake: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val fromLibrary = libraryScanId != null && library.any { it.id == libraryScanId }
    val pages: List<GalleryPage> = remember(library, libraryScanId, pageUri, pageWidth, pageHeight, fromLibrary) {
        if (fromLibrary) {
            library.map { scan ->
                val current = scan.id == libraryScanId
                GalleryPage(
                    id = scan.id,
                    // Live session page (e.g. after rotate) — not the stale library path.
                    uri = if (current && pageUri != null) pageUri else scan.pageUri,
                    title = scan.title,
                    width = if (current) pageWidth else scan.width,
                    height = if (current) pageHeight else scan.height,
                )
            }
        } else {
            listOf(
                GalleryPage(
                    id = libraryScanId ?: "session",
                    uri = pageUri,
                    title = "Page",
                    width = pageWidth,
                    height = pageHeight,
                ),
            )
        }
    }

    val startIndex = remember(pages, libraryScanId) {
        pages.indexOfFirst { it.id == libraryScanId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { pages.size.coerceAtLeast(1) },
    )

    LaunchedEffect(libraryScanId, pages) {
        val target = pages.indexOfFirst { it.id == libraryScanId }
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }

    LaunchedEffect(pagerState, fromLibrary) {
        if (!fromLibrary) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val id = pages.getOrNull(page)?.id ?: return@collect
                if (id != libraryScanId) onSelectScan(id)
            }
    }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var pendingDelete by remember { mutableStateOf(false) }
    val userScrollEnabled = pages.size > 1 && !isGalleryZoomed(zoomScale)
    val pageReady = pageUri != null

    LaunchedEffect(pagerState.currentPage) {
        zoomScale = 1f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraChrome)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Image stage — swipeable gallery + zoom.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (pages.isEmpty() || pages.all { it.uri == null }) {
                Text(
                    "Loading page…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Lime400,
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = userScrollEnabled,
                    beyondViewportPageCount = 1,
                ) { pageIndex ->
                    GalleryPageContent(
                        page = pages[pageIndex],
                        isActive = pageIndex == pagerState.currentPage,
                        onScaleChanged = { if (pageIndex == pagerState.currentPage) zoomScale = it },
                    )
                }
            }

            if (pages.size > 1 && !isGalleryZoomed(zoomScale)) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Lime400.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Bottom tool sheet — matches opened-image reference.
        Surface(
            color = Lime400,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    EditTool(
                        icon = Icons.Outlined.AutoFixHigh,
                        label = "Auto",
                        enabled = pageReady && toolsEnabled,
                        onClick = onAutoEnhance,
                    )
                    EditTool(
                        icon = Icons.Outlined.Crop,
                        label = "Crop",
                        enabled = pageReady && toolsEnabled,
                        onClick = onCrop,
                    )
                    EditTool(
                        icon = Icons.Outlined.Rotate90DegreesCcw,
                        label = "Rotate",
                        enabled = pageReady && toolsEnabled,
                        onClick = onRotate,
                    )
                    EditTool(
                        icon = Icons.Outlined.DocumentScanner,
                        label = "Rescan",
                        enabled = toolsEnabled,
                        onClick = onRetake,
                    )
                    if (fromLibrary) {
                        EditTool(
                            icon = Icons.Outlined.DeleteOutline,
                            label = "Delete",
                            enabled = toolsEnabled,
                            onClick = { pendingDelete = true },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onBack)
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .semantics { contentDescription = "Cancel" },
                    )
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDone)
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .semantics { contentDescription = "Done" },
                    )
                }
            }
        }
    }

    if (pendingDelete && libraryScanId != null) {
        val title = pages.firstOrNull { it.id == libraryScanId }?.title ?: "this scan"
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete scan?") },
            text = { Text("“$title” will be removed from your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        onDelete(libraryScanId)
                    },
                ) {
                    Text("Delete", color = Ink)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EditTool(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Ink else Ink.copy(alpha = 0.35f)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .semantics { contentDescription = label },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
private fun GalleryPageContent(
    page: GalleryPage,
    isActive: Boolean,
    onScaleChanged: (Float) -> Unit,
) {
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val shortSide = LocalConfiguration.current.smallestScreenWidthDp
    val maxSide = galleryPreviewMaxSide(shortSide)
    // Uri path can stay page.jpg after library sync; size + uri together force reload on rotate.
    val reloadKey = "${page.id}|${page.uri}|${page.width}x${page.height}"

    LaunchedEffect(reloadKey, maxSide) {
        preview?.recycle()
        preview = null
        val path = page.uri?.path ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            BitmapDecode.decodeDownsampled(path, maxSide)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = preview
        if (bmp != null) {
            ZoomableImage(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.title,
                resetKey = reloadKey,
                modifier = Modifier.fillMaxSize(),
                onScaleChanged = { if (isActive) onScaleChanged(it) },
            )
        } else {
            Text(
                "Loading…",
                style = MaterialTheme.typography.bodyLarge,
                color = Lime400.copy(alpha = 0.7f),
            )
        }
    }
}

private data class GalleryPage(
    val id: String,
    val uri: Uri?,
    val title: String,
    val width: Int,
    val height: Int,
)
