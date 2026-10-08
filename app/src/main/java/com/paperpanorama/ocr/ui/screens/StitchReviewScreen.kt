package com.paperpanorama.ocr.ui.screens

import android.net.Uri
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperpanorama.ocr.ui.theme.CameraChrome
import com.paperpanorama.ocr.ui.theme.PaperPanoramaTheme
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400

/**
 * Visual stitch audit: scroll inputs → see combined / mosaic result → continue.
 */
@Composable
fun StitchReviewScreen(
    inputUris: List<Uri>,
    resultUri: Uri?,
    usedFallback: Boolean,
    auditPathHint: String?,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    PaperPanoramaTheme(cameraChrome = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                text = "Stitch review",
                style = MaterialTheme.typography.headlineMedium,
                color = Lime400,
            )
            Text(
                text = if (usedFallback) {
                    "Pages stacked (no geometric overlap). Compare inputs vs result."
                } else {
                    "Panorama mosaic. Compare inputs vs result."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Lime400.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Inputs (${inputUris.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Lime400,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Input tiles" },
                ) {
                    itemsIndexed(inputUris, key = { i, u -> "$i:${u}" }) { index, uri ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Input ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(112.dp, 148.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Lime400.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            )
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Lime400,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Result",
                    style = MaterialTheme.typography.titleMedium,
                    color = Lime400,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = CameraChrome.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .border(1.dp, Lime400.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (resultUri != null) {
                            AsyncImage(
                                model = resultUri,
                                contentDescription = "Stitch result",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                            )
                        } else {
                            Text("No result yet", color = Lime400.copy(alpha = 0.7f))
                        }
                    }
                }

                if (!auditPathHint.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Saved for audit:\n$auditPathHint",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lime400.copy(alpha = 0.65f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack, modifier = Modifier.width(100.dp)) {
                    Text("Back", color = Lime400)
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = Ink,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Continue to page")
                }
            }
        }
    }
}
