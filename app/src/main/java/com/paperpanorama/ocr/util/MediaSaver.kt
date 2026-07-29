package com.paperpanorama.ocr.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Copies result images into the device gallery (Pictures/TileOCR). */
object MediaSaver {

    fun saveToGallery(context: Context, source: Uri, displayName: String): Boolean {
        val file = source.path?.let(::File) ?: return false
        if (!file.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/" + ALBUM,
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                true
            } else {
                // Pre-Q public Pictures needs WRITE permission; app external dir is enough for testing.
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
                dir.mkdirs()
                file.copyTo(File(dir, displayName), overwrite = true)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private const val ALBUM = "TileOCR"
}
