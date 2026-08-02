package com.paperpanorama.ocr.ocr

import android.graphics.Bitmap
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MistralOcrClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient(),
) {
    sealed class Result {
        data class Ok(val page: OcrPageResult) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun processJpegFile(file: File, maxLongEdge: Int = 2000): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Err("Set mistral_api_key in .env")
            }
            if (!file.exists()) {
                return@withContext Result.Err("Page image missing")
            }
            val jpegBytes = encodeJpegCapped(file, maxLongEdge)
                ?: return@withContext Result.Err("Could not read page image")
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val body = JSONObject()
                .put("model", "mistral-ocr-latest")
                .put(
                    "document",
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", "data:image/jpeg;base64,$b64"),
                )
                .put("include_blocks", true)
                .toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url(OCR_URL)
                .header("Authorization", "Bearer $apiKey")
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
                            // HTTP errors (4xx/5xx) are not retried — fail immediately
                            val msg = when (resp.code) {
                                401, 403 -> "Mistral API key rejected — check mistral_api_key in .env"
                                429 -> "Mistral rate limit — try again in a moment"
                                else -> OcrJsonParse.parseError(raw) ?: "OCR failed (${resp.code})"
                            }
                            return@withContext Result.Err(msg)
                        }
                        val page = OcrJsonParse.parseResponse(raw)
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
        private const val OCR_URL = "https://api.mistral.ai/v1/ocr"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

        fun encodeJpegCapped(file: File, maxLongEdge: Int): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            val sample = if (longEdge <= maxLongEdge) {
                1
            } else {
                var s = 1
                while (longEdge / s > maxLongEdge * 2) s *= 2
                s
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val scaled = if (max(bmp.width, bmp.height) > maxLongEdge) {
                val scale = maxLongEdge.toFloat() / max(bmp.width, bmp.height)
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, w, h, true).also {
                    if (it != bmp) bmp.recycle()
                }
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()
            return out.toByteArray()
        }
    }
}
