package com.example.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AirReceiveGatewaySender(
    private val context: Context,
    private val serverUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun upload(
        uri: Uri,
        onTransferStarted: (fileName: String, fileSize: Long) -> Unit,
        onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onTransferCompleted: (fileName: String) -> Unit,
        onTransferFailed: (error: String) -> Unit
    ) {
        val resolver = context.contentResolver
        val fileName = queryDisplayName(uri) ?: "photo.jpg"
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        val fileSize = queryFileSize(uri)

        onTransferStarted(fileName, fileSize)

        val uploadUrl = buildUploadUrl()
        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()

            override fun contentLength(): Long =
                if (fileSize > 0) fileSize else -1

            override fun writeTo(sink: okio.BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalSent = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        totalSent += bytesRead
                        val total = if (fileSize > 0) fileSize else totalSent
                        onTransferProgress(totalSent, total)
                    }
                } ?: throw IOException("Could not open image")
            }
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("target", "receiver")
            .addFormDataPart("file", fileName, fileBody)
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(multipart)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    onTransferFailed("Server error: ${response.code} $bodyText")
                    return
                }
                val json = try {
                    JSONObject(bodyText)
                } catch (e: Exception) {
                    onTransferFailed("Invalid server response")
                    return
                }
                if (json.optBoolean("receiverRelayed", false)) {
                    Log.d("AirReceiveGatewaySender", "Upload relayed to iPhone: $fileName")
                    onTransferCompleted(fileName)
                } else {
                    onTransferFailed(
                        "No iPhone receive page connected. Open ${buildReceivePageUrl()} in Safari first and keep it in the foreground."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("AirReceiveGatewaySender", "Upload failed", e)
            onTransferFailed(e.localizedMessage ?: "Upload failed")
        }
    }

    fun buildReceivePageUrl(): String {
        val base = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl.removeSuffix("/")
        } else {
            "https://${serverUrl.removeSuffix("/")}"
        }
        return "$base/receive"
    }

    private fun buildUploadUrl(): String {
        val base = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl.removeSuffix("/")
        } else {
            "https://${serverUrl.removeSuffix("/")}"
        }
        return "$base/upload"
    }

    private fun queryDisplayName(uri: Uri): String? {
        resolver(context).query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return null
    }

    private fun queryFileSize(uri: Uri): Long {
        resolver(context).query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(sizeIndex).coerceAtLeast(0L)
            }
        }
        return 0L
    }

    private fun resolver(context: Context) = context.contentResolver
}
