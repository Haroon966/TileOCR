package com.paperpanorama.ocr.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paperpanorama.ocr.ui.theme.DeepRichRed
import com.paperpanorama.ocr.ui.theme.SoftYellow

@Composable
fun CameraPermissionDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Camera access",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        text = {
            Text(
                text = "TileOCR needs the camera to capture document tiles for panorama stitching. Photos stay on your device.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepRichRed,
                    contentColor = SoftYellow,
                ),
            ) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
        containerColor = SoftYellow,
        titleContentColor = DeepRichRed,
        textContentColor = DeepRichRed,
    )
}
