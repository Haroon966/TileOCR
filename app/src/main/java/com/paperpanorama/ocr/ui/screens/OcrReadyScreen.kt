package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.theme.DeepRichRed
import com.paperpanorama.ocr.ui.theme.SoftYellow
import com.paperpanorama.ocr.util.BitmapDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OcrReadyScreen(
    pageUri: Uri?,
    pageWidth: Int,
    pageHeight: Int,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onRetake: () -> Unit,
    onDone: () -> Unit,
) {
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageUri) {
        preview?.recycle()
        preview = null
        val path = pageUri?.path ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            BitmapDecode.decodeDownsampled(path, 1600)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCrop,
                enabled = pageUri != null,
                modifier = Modifier.semantics { contentDescription = "Crop page" },
            ) {
                Text("Crop", color = DeepRichRed)
            }
            Text("OCR ready", style = MaterialTheme.typography.titleLarge, color = DeepRichRed)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onRotate,
                    modifier = Modifier.semantics { contentDescription = "Rotate page" },
                ) { Text("Rotate", color = DeepRichRed) }
                TextButton(onClick = onRetake) { Text("Retake", color = DeepRichRed) }
            }
        }

        Surface(
            color = DeepRichRed.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (pageWidth > 0) {
                    "Flat page ready for OCR (${pageWidth}×${pageHeight}). Recognition comes next."
                } else {
                    "Flat page ready for OCR. Recognition comes next."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
                color = DeepRichRed,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "OCR-ready page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Loading page…", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp)
                .semantics { contentDescription = "Done" },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeepRichRed,
                contentColor = SoftYellow,
            ),
        ) {
            Text("Done", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
