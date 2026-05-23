package com.example.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class AirReceiveLocalSender(
    private val context: Context,
    targetBaseUrl: String
) {
    companion object {
        const val MAX_BATCH_FILES = 20
    }

    private val uploadUrl: String = normalizeUploadUrl(targetBaseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun probeReceiver(): Boolean {
        val baseUrl = uploadUrl.removeSuffix("/upload")
        val request = Request.Builder().url(baseUrl).get().build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w("AirReceiveLocalSender", "Probe failed: ${e.message}")
            false
        }
    }

    fun uploadBatch(
        uris: List<Uri>,
        onTransferStarted: (label: String, totalSize: Long) -> Unit,
        onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onTransferCompleted: (fileCount: Int) -> Unit,
        onTransferFailed: (error: String) -> Unit
    ) {
        if (uris.isEmpty()) return
        if (uris.size > MAX_BATCH_FILES) {
            onTransferFailed("Maximum $MAX_BATCH_FILES files per batch.")
            return
        }

        val resolver = context.contentResolver
        val items = uris.map { uri ->
            Triple(uri, queryDisplayName(uri) ?: "file", queryFileSize(uri))
        }
        val knownTotal = items.sumOf { it.third }
        val totalBytes = if (knownTotal > 0) knownTotal else -1L
        val label = if (uris.size == 1) items.first().second else "${uris.size} files"

        onTransferStarted(label, if (totalBytes > 0) totalBytes else uris.size.toLong())

        var sentCount = 0
        val progressCounter = longArrayOf(0L)

        for ((uri, fileName, fileSize) in items) {
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val fileBody = object : okhttp3.RequestBody() {
                override fun contentType() = mimeType.toMediaTypeOrNull()

                override fun contentLength(): Long =
                    if (fileSize > 0) fileSize else -1

                override fun writeTo(sink: okio.BufferedSink) {
                    resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                            synchronized(progressCounter) {
                                progressCounter[0] += bytesRead
                                val sent = progressCounter[0]
                                val total = if (totalBytes > 0) totalBytes else sent
                                onTransferProgress(sent, total)
                            }
                        }
                    } ?: throw IOException("Could not open file: $fileName")
                }
            }

            val encodedName = URLEncoder.encode(fileName, "UTF-8")
            val request = Request.Builder()
                .url(uploadUrl)
                .post(fileBody)
                .header("X-File-Name", encodedName)
                .header("Content-Type", mimeType)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        onTransferFailed(
                            "Upload failed for $fileName (${response.code}). " +
                                "Ensure the receiver has started and the URL is correct."
                        )
                        return
                    }
                    if (!bodyText.contains("Success", ignoreCase = true)) {
                        onTransferFailed("Unexpected response from receiver for $fileName")
                        return
                    }
                    sentCount++
                }
            } catch (e: UnknownHostException) {
                Log.e("AirReceiveLocalSender", "Upload failed: host not found", e)
                onTransferFailed(
                    "Cannot reach $uploadUrl. Check the receiver URL and that both devices are on the same Wi-Fi."
                )
                return
            } catch (e: IOException) {
                Log.e("AirReceiveLocalSender", "Upload failed", e)
                onTransferFailed(
                    e.localizedMessage ?: "Network error sending $fileName"
                )
                return
            } catch (e: Exception) {
                Log.e("AirReceiveLocalSender", "Upload failed", e)
                onTransferFailed(e.localizedMessage ?: "Upload failed")
                return
            }
        }

        onTransferCompleted(sentCount)
    }

    private fun normalizeUploadUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return if (url.endsWith("/upload", ignoreCase = true)) {
            url
        } else {
            "$url/upload"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun queryFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(sizeIndex).coerceAtLeast(0L)
            }
        }
        return 0L
    }
}
