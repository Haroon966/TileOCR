package com.paperpanorama.ocr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.library.ScanLibrary
import com.paperpanorama.ocr.ui.theme.DeepRichRed
import com.paperpanorama.ocr.ui.theme.SoftYellow

/**
 * Home = library. Exaggerated minimalism on brand SoftYellow / DeepRichRed:
 * oversized brand mark, recents grid, sticky New scan CTA, purposeful empty state.
 */
@Composable
fun HomeScreen(
    scans: List<SavedScan>,
    onNewScan: () -> Unit,
    onOpenScan: (String) -> Unit,
    onDeleteScan: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SavedScan?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SoftYellow,
                        SoftYellow,
                        DeepRichRed.copy(alpha = 0.10f),
                    ),
                ),
            ),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 28.dp,
                bottom = 112.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                HomeHeader(scanCount = scans.size)
            }

            if (scans.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    EmptyLibrary(onNewScan = onNewScan)
                }
            } else {
                item(span = { GridItemSpan(2) }) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeepRichRed.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(scans, key = { it.id }) { scan ->
                    ScanTile(
                        scan = scan,
                        onOpen = { onOpenScan(scan.id) },
                        onDelete = { pendingDelete = scan },
                    )
                }
            }
        }

        // Sticky bottom CTA — always available (empty or full library).
        Surface(
            color = SoftYellow.copy(alpha = 0.92f),
            shadowElevation = 0.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                onClick = onNewScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .height(56.dp)
                    .semantics { contentDescription = "New scan" },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepRichRed,
                    contentColor = SoftYellow,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("New scan", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    pendingDelete?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete scan?") },
            text = {
                Text("“${scan.title}” will be removed from your library.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteScan(scan.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = DeepRichRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun HomeHeader(scanCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = "TileOCR",
            style = MaterialTheme.typography.displayLarge,
            color = DeepRichRed,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (scanCount == 0) {
                "Scan pages in overlapping shots"
            } else {
                "$scanCount ${if (scanCount == 1) "scan" else "scans"} ready for OCR"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = DeepRichRed.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun EmptyLibrary(onNewScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.DocumentScanner,
            contentDescription = null,
            tint = DeepRichRed.copy(alpha = 0.45f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No scans yet",
            style = MaterialTheme.typography.headlineMedium,
            color = DeepRichRed,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Capture overlapping photos of a page.\nWe’ll stitch and crop it for OCR.",
            style = MaterialTheme.typography.bodyMedium,
            color = DeepRichRed.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(modifier = Modifier.height(28.dp))
        TextButton(onClick = onNewScan) {
            Text("Start your first scan", color = DeepRichRed)
        }
    }
}

@Composable
private fun ScanTile(
    scan: SavedScan,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(DeepRichRed.copy(alpha = 0.08f))
                .clickable(onClick = onOpen)
                .semantics { contentDescription = "Open ${scan.title}" },
        ) {
            AsyncImage(
                model = scan.pageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .semantics { contentDescription = "Delete ${scan.title}" },
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = SoftYellow,
                    modifier = Modifier
                        .background(DeepRichRed.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = scan.title,
            style = MaterialTheme.typography.titleMedium,
            color = DeepRichRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onOpen),
        )
        Text(
            text = ScanLibrary.formatDate(scan.createdAtMs),
            style = MaterialTheme.typography.labelMedium,
            color = DeepRichRed.copy(alpha = 0.55f),
        )
    }
}
