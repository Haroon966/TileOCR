package com.paperpanorama.ocr.domain

import android.net.Uri

/** One OCR-ready page kept in the local library. */
data class SavedScan(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val width: Int,
    val height: Int,
    val pageUri: Uri,
    val mosaicUri: Uri? = null,
)
