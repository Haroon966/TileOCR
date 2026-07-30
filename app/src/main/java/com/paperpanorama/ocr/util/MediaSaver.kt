package com.paperpanorama.ocr.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Copies result images into the device gallery (Pictures/TileOCR). */
object MediaSaver {

    private const val ALBUM = "TileOCR"

    fun saveToGallery(context: Context, source: Uri, displayName: String): Boolean {
        deleteByDisplayName(context, displayName)
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
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
                dir.mkdirs()
                file.copyTo(File(dir, displayName), overwrite = true)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Remove a previous gallery export with the same display name (stable id names). */
    fun deleteByDisplayName(context: Context, displayName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection =
                    "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val args = arrayOf(displayName, "%$ALBUM%")
                context.contentResolver.query(collection, projection, selection, args, null)
                    ?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        var deleted = false
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val uri = ContentUris.withAppendedId(collection, id)
                            deleted = context.contentResolver.delete(uri, null, null) > 0 || deleted
                        }
                        deleted
                    } ?: false
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
                val f = File(dir, displayName)
                f.exists() && f.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun pageDisplayName(libraryId: String): String = "page_$libraryId.jpg"
}
