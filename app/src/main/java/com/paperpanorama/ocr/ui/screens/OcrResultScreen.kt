package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.components.ZoomableImage
import com.paperpanorama.ocr.ui.theme.Canvas
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import com.paperpanorama.ocr.util.BitmapDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Design-system Result step: white reconstructed OCR page + Copy / Save.
 */
@Composable
fun OcrResultScreen(
    cleanUri: Uri?,
    markdown: String,
    isRunning: Boolean,
    error: String?,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Extracted Text",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Back",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .semantics { contentDescription = "Back" },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            when {
                isRunning -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = Lime400)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Running OCR…", color = Ink)
                    }
                }
                error != null -> {
                    Text(
                        text = error,
                        color = Ink,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                }
                cleanUri != null -> {
                    var bmp by remember(cleanUri) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(cleanUri) {
                        val path = cleanUri.path ?: return@LaunchedEffect
                        bmp = withContext(Dispatchers.IO) {
                            BitmapDecode.decodeDownsampled(path, 2048)
                        }
                    }
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val image = bmp
                        if (image != null) {
                            ZoomableImage(
                                bitmap = image.asImageBitmap(),
                                contentDescription = "Clean OCR page",
                                resetKey = cleanUri,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        if (markdown.isNotBlank() && !isRunning && error == null) {
            Text(
                text = markdown.take(400) + if (markdown.length > 400) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(72.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        Surface(
            color = Lime400,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Text(
                    text = "Copy",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = markdown.isNotBlank(), onClick = onCopy)
                        .padding(12.dp)
                        .semantics { contentDescription = "Copy" },
                )
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = cleanUri != null && !isRunning, onClick = onSave)
                        .padding(12.dp)
                        .semantics { contentDescription = "Save" },
                )
            }
        }
    }
}
