package com.paperpanorama.ocr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.theme.Radius
import com.paperpanorama.ocr.ui.theme.Spacing

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface, shape)
        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(Spacing.md)
    Column(modifier = base, content = content)
}

@Composable
fun FileCard(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    AppCard(modifier = modifier, onClick = onClick) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
