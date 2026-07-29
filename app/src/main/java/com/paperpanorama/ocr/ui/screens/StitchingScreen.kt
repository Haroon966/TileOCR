package com.paperpanorama.ocr.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.theme.PaperPanoramaTheme
import com.paperpanorama.ocr.ui.theme.SoftYellow

@Composable
fun StitchingScreen(
    progress: Float,
    message: String,
    onCancel: () -> Unit,
) {
    PaperPanoramaTheme(cameraChrome = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Stitching",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = message.ifBlank { "Working…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
                val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "stitch")
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .semantics { contentDescription = "Stitch progress ${(animated * 100).toInt()} percent" },
                    color = SoftYellow,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}
