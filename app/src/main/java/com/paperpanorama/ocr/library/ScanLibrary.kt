package com.paperpanorama.ocr.library

import android.content.Context
import android.net.Uri
import com.paperpanorama.ocr.domain.SavedScan
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Local scan library under filesDir/scans/<id>/.
 * Survives restart; cacheDir tiles do not.
 */
class ScanLibrary(context: Context) {
    private val root = File(context.filesDir, "scans").also { it.mkdirs() }

    fun list(): List<SavedScan> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readMeta(it) }
            .sortedByDescending { it.createdAtMs }
    }

    fun get(id: String): SavedScan? {
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        return readMeta(dir)
    }

    /**
     * Copies the page (and optional mosaic) into a durable library entry.
     * Returns the new [SavedScan], or null if the page file is missing.
     */
    fun save(
        pageUri: Uri,
        mosaicUri: Uri?,
        width: Int,
        height: Int,
        title: String? = null,
    ): SavedScan? {
        val pageSrc = pageUri.path?.let(::File) ?: return null
        if (!pageSrc.exists()) return null
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).also { it.mkdirs() }
        val pageDst = File(dir, PAGE_NAME)
        pageSrc.copyTo(pageDst, overwrite = true)
        var mosaicDst: File? = null
        mosaicUri?.path?.let { path ->
            val src = File(path)
            if (src.exists()) {
                mosaicDst = File(dir, MOSAIC_NAME)
                src.copyTo(mosaicDst!!, overwrite = true)
            }
        }
        val createdAt = System.currentTimeMillis()
        val resolvedTitle = title?.takeIf { it.isNotBlank() }
            ?: defaultTitle(createdAt)
        writeMeta(
            dir = dir,
            id = id,
            title = resolvedTitle,
            createdAtMs = createdAt,
            width = width,
            height = height,
            hasMosaic = mosaicDst != null,
        )
        return SavedScan(
            id = id,
            title = resolvedTitle,
            createdAtMs = createdAt,
            width = width,
            height = height,
            pageUri = Uri.fromFile(pageDst),
            mosaicUri = mosaicDst?.let { Uri.fromFile(it) },
        )
    }

    /** Replace the page image for an existing entry (e.g. after rotate). */
    fun updatePage(id: String, pageUri: Uri, width: Int, height: Int): SavedScan? {
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        val src = pageUri.path?.let(::File) ?: return null
        if (!src.exists()) return null
        val pageDst = File(dir, PAGE_NAME)
        src.copyTo(pageDst, overwrite = true)
        val prev = readMeta(dir) ?: return null
        writeMeta(
            dir = dir,
            id = id,
            title = prev.title,
            createdAtMs = prev.createdAtMs,
            width = width,
            height = height,
            hasMosaic = prev.mosaicUri != null,
        )
        return get(id)
    }

    fun updateTitle(id: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return false
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        val metaFile = File(dir, META_NAME)
        if (!metaFile.exists()) return false
        return try {
            val json = JSONObject(metaFile.readText())
            json.put("title", trimmed)
            metaFile.writeText(json.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun delete(id: String): Boolean {
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    private fun readMeta(dir: File): SavedScan? {
        val metaFile = File(dir, META_NAME)
        val page = File(dir, PAGE_NAME)
        if (!page.exists()) return null
        return try {
            val json = if (metaFile.exists()) {
                JSONObject(metaFile.readText())
            } else {
                JSONObject()
            }
            val id = json.optString("id", dir.name)
            val createdAt = json.optLong("createdAtMs", dir.lastModified())
            val title = json.optString("title", defaultTitle(createdAt))
            val width = json.optInt("width", 0)
            val height = json.optInt("height", 0)
            val mosaic = File(dir, MOSAIC_NAME).takeIf { it.exists() }
            SavedScan(
                id = id,
                title = title,
                createdAtMs = createdAt,
                width = width,
                height = height,
                pageUri = Uri.fromFile(page),
                mosaicUri = mosaic?.let { Uri.fromFile(it) },
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeMeta(
        dir: File,
        id: String,
        title: String,
        createdAtMs: Long,
        width: Int,
        height: Int,
        hasMosaic: Boolean,
    ) {
        val json = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("createdAtMs", createdAtMs)
            .put("width", width)
            .put("height", height)
            .put("hasMosaic", hasMosaic)
        File(dir, META_NAME).writeText(json.toString())
    }

    companion object {
        private const val PAGE_NAME = "page.jpg"
        private const val MOSAIC_NAME = "mosaic.jpg"
        private const val META_NAME = "meta.json"

        fun defaultTitle(createdAtMs: Long): String {
            val fmt = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
            return fmt.format(Date(createdAtMs))
        }

        fun formatDate(createdAtMs: Long): String {
            val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            return fmt.format(Date(createdAtMs))
        }
    }
}
