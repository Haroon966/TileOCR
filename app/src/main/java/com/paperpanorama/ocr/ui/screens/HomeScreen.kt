package com.paperpanorama.ocr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.ui.components.PrimaryButton
import com.paperpanorama.ocr.ui.theme.Radius
import com.paperpanorama.ocr.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREVIEW_PER_MONTH = 6

/**
 * Home gallery — Canvas / lime brand, Tap-to-scan empty state, FAB for new scans.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    scans: List<SavedScan>,
    onNewScan: () -> Unit,
    onOpenScan: (String) -> Unit,
    onDeleteScan: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SavedScan?>(null) }
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
                            onOpen = onOpenScan,
                            onDelete = { pendingDelete = it },
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewScan,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 20.dp)
                .semantics { contentDescription = "New scan" },
            shape = CircleShape,
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
        ) {
            Icon(
                imageVector = Icons.Outlined.DocumentScanner,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
        }
    }

    pendingDelete?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete scan?") },
            text = { Text("“${scan.title}” will be removed from your library.") },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthPhotoGrid(
    scans: List<SavedScan>,
    onOpen: (String) -> Unit,
    onDelete: (SavedScan) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        scans.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { scan ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(scheme.surfaceVariant)
                            .border(1.dp, scheme.outline, RoundedCornerShape(Radius.md))
                            .combinedClickable(
                                onClick = { onOpen(scan.id) },
                                onLongClick = { onDelete(scan) },
                            )
                            .semantics {
                                contentDescription =
                                    "Open ${scan.title}. Long press to delete."
                            },
                    ) {
                        AsyncImage(
                            model = scan.pageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
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
                Icon(
                    imageVector = Icons.Outlined.DocumentScanner,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(72.dp),
                )
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
