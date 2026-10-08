package com.paperpanorama.ocr.ocr

import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Google Cloud Vision `DOCUMENT_TEXT_DETECTION` client — returns per-word text with pixel
 * bounding boxes, for building a searchable PDF (original scan image + invisible text layer).
 */
class GoogleVisionOcrClient(
    private val apiKey: String,
    private val http: OkHttpClient = MistralOcrClient.defaultClient(),
) {
    sealed class Result {
        data class Ok(val page: OcrPageResult) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun processJpegFile(file: File, maxLongEdge: Int = 2000): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Err("Set GOOGLE_VISION_API_KEY in .env")
            }
            if (!file.exists()) {
                return@withContext Result.Err("Page image missing")
            }
            val jpegBytes = MistralOcrClient.encodeJpegCapped(file, maxLongEdge)
                ?: return@withContext Result.Err("Could not read page image")
            val (imgW, imgH) = decodeBounds(jpegBytes)
                ?: return@withContext Result.Err("Could not read page image")
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val body = JSONObject()
                .put(
                    "requests",
                    JSONArray().put(
                        JSONObject()
                            .put("image", JSONObject().put("content", b64))
                            .put(
                                "features",
                                JSONArray().put(JSONObject().put("type", "DOCUMENT_TEXT_DETECTION")),
                            ),
                    ),
                )
                .toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("$VISION_URL?key=$apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            var lastErr: Result.Err = Result.Err("Network error")
            repeat(MAX_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RETRY_DELAY_MS)
                try {
                    http.newCall(request).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val apiMsg = GoogleVisionJsonParse.parseError(raw)
                            val msg = when {
                                apiMsg != null -> apiMsg
                                resp.code == 429 -> "Google Vision rate limit — try again in a moment"
                                resp.code == 400 || resp.code == 401 || resp.code == 403 ->
                                    "Google Vision API key rejected — check GOOGLE_VISION_API_KEY in .env"
                                else -> "OCR failed (${resp.code})"
                            }
                            return@withContext Result.Err(msg)
                        }
                        GoogleVisionJsonParse.parseError(raw)?.let { errMsg ->
                            return@withContext Result.Err(errMsg)
                        }
                        val page = GoogleVisionJsonParse.parseResponse(raw, imgW, imgH)
                            ?: return@withContext Result.Err("Empty OCR response")
                        return@withContext Result.Ok(page)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastErr = Result.Err(
                        if (attempt < MAX_ATTEMPTS - 1) "Retrying… (${t.message})"
                        else t.message ?: "Network error"
                    )
                }
            }
            lastErr
        }

    companion object {
        private const val VISION_URL = "https://vision.googleapis.com/v1/images:annotate"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            return opts.outWidth to opts.outHeight
        }
    }
}
