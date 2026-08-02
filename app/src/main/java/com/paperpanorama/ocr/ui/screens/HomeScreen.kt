package com.paperpanorama.ocr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.ui.components.PrimaryButton
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import com.paperpanorama.ocr.ui.theme.Radius
import com.paperpanorama.ocr.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREVIEW_PER_MONTH = 6

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    scans: List<SavedScan>,
    onNewScan: () -> Unit,
    onOpenScan: (String) -> Unit,
    onDeleteScan: (String) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    isBuildingBatchPdf: Boolean = false,
    batchPdfProgress: String = "",
    onBuildCombinedPdf: (List<String>) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<SavedScan?>(null) }
    var pendingActionScan by remember { mutableStateOf<SavedScan?>(null) }
    var pendingRename by remember { mutableStateOf<SavedScan?>(null) }
    var renameText by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var expandedMonths by remember { mutableStateOf(setOf<String>()) }
    val sections = remember(scans) { groupScansByMonth(scans) }
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = 20.dp,
                bottom = 100.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TileOCR",
                            style = MaterialTheme.typography.displayLarge,
                            color = scheme.onBackground,
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs + 2.dp))
                        Text(
                            text = if (scans.isEmpty()) {
                                "Scan pages into your gallery"
                            } else {
                                "${scans.size} ${if (scans.size == 1) "page" else "pages"} · tap to open"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (scans.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectionMode = !selectionMode
                                selectedIds = emptySet()
                            },
                        ) {
                            Text(
                                text = if (selectionMode) "Cancel" else "Select",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selectionMode) scheme.onSurfaceVariant else Lime400,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (scans.isEmpty()) {
                item {
                    EmptyGallery(onNewScan = onNewScan)
                }
            } else {
                sections.forEach { section ->
                    item(key = "header-${section.key}") {
                        MonthSectionHeader(
                            label = section.label,
                            total = section.scans.size,
                            expanded = section.key in expandedMonths ||
                                section.scans.size <= PREVIEW_PER_MONTH,
                            onViewAll = {
                                expandedMonths = if (section.key in expandedMonths) {
                                    expandedMonths - section.key
                                } else {
                                    expandedMonths + section.key
                                }
                            },
                        )
                    }
                    item(key = "grid-${section.key}") {
                        val showAll = section.key in expandedMonths ||
                            section.scans.size <= PREVIEW_PER_MONTH
                        val visible = if (showAll) section.scans else section.scans.take(PREVIEW_PER_MONTH)
                        MonthPhotoGrid(
                            scans = visible,
                            selectionMode = selectionMode,
                            selectedIds = selectedIds,
                            onTap = { id ->
                                if (selectionMode) {
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                } else {
                                    onOpenScan(id)
                                }
                            },
                            onLongPress = { scan ->
                                if (selectionMode) {
                                    selectedIds = if (scan.id in selectedIds) selectedIds - scan.id else selectedIds + scan.id
                                } else {
                                    pendingActionScan = scan
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }

        // FAB — hidden in selection mode
        AnimatedVisibility(
            visible = !selectionMode,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 20.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = onNewScan,
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(50.dp))
                    .semantics { contentDescription = "New scan" },
                containerColor = Lime400,
                contentColor = Ink,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.DocumentScanner,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                text = {
                    Text(
                        text = "New Scan",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        }

        // Selection action bar — slides up from bottom
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            SelectionBar(
                count = selectedIds.size,
                onCombinePdf = {
                    val ids = selectedIds.toList()
                    selectionMode = false
                    selectedIds = emptySet()
                    onBuildCombinedPdf(ids)
                },
                onCancel = {
                    selectionMode = false
                    selectedIds = emptySet()
                },
            )
        }

        // Batch PDF progress overlay
        if (isBuildingBatchPdf) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.48f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surface,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "Building PDF…",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Lime400,
                            trackColor = Lime400.copy(alpha = 0.2f),
                        )
                        if (batchPdfProgress.isNotBlank()) {
                            Text(
                                text = batchPdfProgress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press action dialog: Rename / Delete / Cancel
    pendingActionScan?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingActionScan = null },
            title = { Text(scan.title) },
            text = null,
            confirmButton = {
                TextButton(onClick = {
                    renameText = scan.title
                    pendingRename = scan
                    pendingActionScan = null
                }) {
                    Text("Rename", color = scheme.onBackground)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingDelete = scan
                        pendingActionScan = null
                    }) {
                        Text("Delete", color = Color(0xFFFF6B6B))
                    }
                    TextButton(onClick = { pendingActionScan = null }) {
                        Text("Cancel", color = scheme.onSurfaceVariant)
                    }
                }
            },
        )
    }

    // Rename dialog
    pendingRename?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename scan") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) onRename(scan.id, trimmed)
                        pendingRename = null
                    },
                ) {
                    Text("Save", color = Lime400)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text("Cancel", color = scheme.onSurfaceVariant)
                }
            },
        )
    }

    // Delete confirmation
    pendingDelete?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete scan?") },
            text = { Text("\"${scan.title}\" will be removed from your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteScan(scan.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = scheme.onBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = scheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onCombinePdf: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = Ink,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (count == 0) "Select pages" else "$count selected",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
            }
            val canCombine = count >= 2
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50.dp))
                    .background(if (canCombine) Lime400 else Color.White.copy(alpha = 0.12f))
                    .clickable(enabled = canCombine, onClick = onCombinePdf)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Combine PDF",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canCombine) Ink else Color.White.copy(alpha = 0.35f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MonthSectionHeader(
    label: String,
    total: Int,
    expanded: Boolean,
    onViewAll: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (total > PREVIEW_PER_MONTH) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClick = onViewAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = if (expanded) "Show fewer" else "View all $total"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (expanded) "Show less" else "View All",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                if (!expanded) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(bottom = 8.dp),
        color = scheme.outline.copy(alpha = 0.6f),
        thickness = 1.dp,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthPhotoGrid(
    scans: List<SavedScan>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onTap: (String) -> Unit,
    onLongPress: (SavedScan) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scans.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { scan ->
                    val selected = scan.id in selectedIds
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .shadow(3.dp, RoundedCornerShape(Radius.md))
                            .combinedClickable(
                                onClick = { onTap(scan.id) },
                                onLongClick = { onLongPress(scan) },
                            )
                            .semantics {
                                contentDescription = if (selectionMode) {
                                    "${if (selected) "Selected" else "Not selected"}: ${scan.title}"
                                } else {
                                    "Open ${scan.title}. Long press for options."
                                }
                            },
                        shape = RoundedCornerShape(Radius.md),
                        color = scheme.surfaceVariant,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = scan.pageUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                                        ),
                                    ),
                            )
                            Text(
                                text = scan.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 6.dp, bottom = 5.dp),
                            )
                            // Checkmark in selection mode
                            if (selectionMode) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(5.dp)
                                        .size(22.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = if (selected) Lime400 else Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyGallery(onNewScan: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dashShape = RoundedCornerShape(Radius.xxl)
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
            scaleIn(initialScale = 0.96f, animationSpec = tween(280, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(120)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(dashShape)
                    .border(
                        width = 2.dp,
                        color = scheme.outline,
                        shape = dashShape,
                    )
                    .background(scheme.surfaceVariant)
                    .clickable(onClick = onNewScan)
                    .semantics { contentDescription = "Start your first scan" }
                    .padding(vertical = Spacing.huge, horizontal = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Lime400.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DocumentScanner,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
                Text(
                    text = "Tap to scan",
                    style = MaterialTheme.typography.headlineMedium,
                    color = scheme.onBackground,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Capture overlapping shots of a page.\nStitch, crop, and save — all on device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.lg))
            PrimaryButton(
                text = "Scan Document",
                onClick = onNewScan,
            )
        }
    }
}

private data class MonthSection(
    val key: String,
    val label: String,
    val sortKey: Long,
    val scans: List<SavedScan>,
)

private fun groupScansByMonth(scans: List<SavedScan>): List<MonthSection> {
    if (scans.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val labelFmt = SimpleDateFormat("MMM", Locale.getDefault())
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)

    return scans
        .groupBy { scan ->
            cal.timeInMillis = scan.createdAtMs
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            "%04d-%02d".format(y, m)
        }
        .map { (key, group) ->
            cal.timeInMillis = group.first().createdAtMs
            val year = cal.get(Calendar.YEAR)
            val monthLabel = labelFmt.format(Date(group.first().createdAtMs))
            val label = if (year == thisYear) monthLabel else "$monthLabel $year"
            MonthSection(
                key = key,
                label = label,
                sortKey = year * 100L + cal.get(Calendar.MONTH),
                scans = group.sortedByDescending { it.createdAtMs },
            )
        }
        .sortedByDescending { it.sortKey }
}
