package com.paperpanorama.ocr.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.components.TertiaryButton
import com.paperpanorama.ocr.ui.theme.PaperPanoramaTheme
import com.paperpanorama.ocr.ui.theme.Spacing

@Composable
fun StitchingScreen(
    progress: Float,
    message: String,
    onCancel: () -> Unit,
) {
    PaperPanoramaTheme(cameraChrome = false) {
        val scheme = MaterialTheme.colorScheme
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            label = "stitch",
        )
        val pct = (animated * 100).toInt()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier
                            .size(120.dp)
                            .semantics {
                                contentDescription = "Stitch progress $pct percent"
                            },
                        color = scheme.primary,
                        trackColor = scheme.surfaceVariant,
                        strokeWidth = 8.dp,
                    )
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = scheme.onBackground,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Processing",
                    style = MaterialTheme.typography.headlineLarge,
                    color = scheme.onBackground,
                )
                Text(
                    text = message.ifBlank { "Working…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.lg))
                TertiaryButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}
