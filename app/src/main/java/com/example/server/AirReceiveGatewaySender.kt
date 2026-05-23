package com.example.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class GatewayReceiverDevice(
    val id: String,
    val displayName: String
)

class AirReceiveGatewaySender(
    private val context: Context,
    private val serverUrl: String
) {
    companion object {
        const val MAX_BATCH_FILES = 20
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
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
        } catch (e: UnknownHostException) {
            Log.e("AirReceiveGatewaySender", "Upload failed: bad gateway host", e)
            onTransferFailed(
                "Cannot reach the gateway server. Check the URL in settings — " +
                    "it must match your Render app exactly (e.g. https://airreceive-repo.onrender.com). " +
                    "Host not found: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e("AirReceiveGatewaySender", "Upload failed", e)
            onTransferFailed(e.localizedMessage ?: "Upload failed")
        }
    }

    fun fetchOnlineReceivers(): List<GatewayReceiverDevice> {
        val url = "${gatewayBaseUrl()}/api/devices?role=receiver"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyText = response.body?.string().orEmpty()
                val json = JSONObject(bodyText)
                val receivers = json.optJSONArray("receivers") ?: JSONArray()
                buildList {
                    for (i in 0 until receivers.length()) {
                        val item = receivers.getJSONObject(i)
                        add(
                            GatewayReceiverDevice(
                                id = item.getString("id"),
                                displayName = item.optString("displayName", "Device")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AirReceiveGatewaySender", "Failed to fetch receivers", e)
            emptyList()
        }
    }

    fun uploadBatch(
        uris: List<Uri>,
        targetDeviceId: String? = null,
        onTransferStarted: (label: String, totalSize: Long) -> Unit,
        onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onTransferCompleted: (photoCount: Int) -> Unit,
        onTransferFailed: (error: String) -> Unit
    ) {
        if (uris.isEmpty()) return
        if (uris.size > MAX_BATCH_FILES) {
            onTransferFailed("Maximum $MAX_BATCH_FILES photos per batch.")
            return
        }

        val resolver = context.contentResolver
        val items = uris.map { uri ->
            Triple(uri, queryDisplayName(uri) ?: "photo.jpg", queryFileSize(uri))
        }
        val knownTotal = items.sumOf { it.third }
        val totalBytes = if (knownTotal > 0) knownTotal else -1L
        val label = "${uris.size} photos"

        onTransferStarted(label, if (totalBytes > 0) totalBytes else uris.size.toLong())

        val progressCounter = longArrayOf(0L)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("target", "receiver")
        if (!targetDeviceId.isNullOrBlank()) {
            multipart.addFormDataPart("targetDeviceId", targetDeviceId)
        }

        for ((uri, fileName, fileSize) in items) {
            val mimeType = resolver.getType(uri) ?: "image/jpeg"
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
                    } ?: throw IOException("Could not open image: $fileName")
                }
            }
            multipart.addFormDataPart("files", fileName, fileBody)
        }

        val request = Request.Builder()
            .url(buildBatchUploadUrl())
            .post(multipart.build())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = try {
                        JSONObject(bodyText).optString("error")
                    } catch (_: Exception) {
                        ""
                    }
                    val detail = err.ifEmpty { bodyText }
                    if (response.code == 404) {
                        onTransferFailed(
                            "Receiver is offline. Open ${buildReceivePageUrl()} on the target device, then tap Refresh."
                        )
                    } else {
                        onTransferFailed("Server error: ${response.code} $detail")
                    }
                    return
                }
                val json = try {
                    JSONObject(bodyText)
                } catch (e: Exception) {
                    onTransferFailed("Invalid server response")
                    return
                }
                if (json.optBoolean("receiverRelayed", false)) {
                    val count = json.optInt("count", uris.size)
                    Log.d("AirReceiveGatewaySender", "Batch relayed to receiver: $count file(s)")
                    onTransferCompleted(count)
                } else {
                    onTransferFailed(
                        "No receive page connected. Open ${buildReceivePageUrl()} on the target device first."
                    )
                }
            }
        } catch (e: UnknownHostException) {
            Log.e("AirReceiveGatewaySender", "Batch upload failed: bad gateway host", e)
            onTransferFailed(
                "Cannot reach the gateway server. Check the URL in settings — " +
                    "it must match your Render app exactly (e.g. https://airreceive-repo.onrender.com). " +
                    "Host not found: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e("AirReceiveGatewaySender", "Batch upload failed", e)
            onTransferFailed(e.localizedMessage ?: "Batch upload failed")
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
        val base = gatewayBaseUrl()
        return "$base/upload"
    }

    private fun buildBatchUploadUrl(): String {
        val base = gatewayBaseUrl()
        return "$base/upload/batch"
    }

    private fun gatewayBaseUrl(): String {
        return if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl.removeSuffix("/")
        } else {
            "https://${serverUrl.removeSuffix("/")}"
        }
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
