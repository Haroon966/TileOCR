package com.paperpanorama.ocr.camera

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Google ML Kit Document Scanner — primary capture UI.
 * High [PAGE_LIMIT]; app then normalizes / crops / stitches pages into one OCR-ready image.
 */
object MlKitDocumentScan {
    /** Practical ceiling for multi-shot docs (Google UI lets user stop earlier). */
    const val PAGE_LIMIT = 50

    fun options(): GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(PAGE_LIMIT)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

    fun startScanIntent(activity: Activity): Task<IntentSender> =
        GmsDocumentScanning.getClient(options()).getStartScanIntent(activity)

    fun pageImageUris(data: Intent?): List<android.net.Uri> {
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
        return result.pages.orEmpty().mapNotNull { it.imageUri }
    }
}
