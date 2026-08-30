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
    val displayName: String,
    val passwordProtection: Boolean = false,
    val role: String = "receiver"
)

class AirReceiveGatewaySender(
    private val context: Context,
    private val serverUrl: String
) {
    companion object {
        const val CHUNK_MAX_FILES = 50
        const val MAX_BATCH_BYTES = 100L * 1024 * 1024
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

    fun fetchOnlineReceivers(): List<GatewayReceiverDevice> =
        fetchDevicesByRole("receiver", "receivers")

    fun fetchOnlinePhones(): List<GatewayReceiverDevice> =
        fetchDevicesByRole("phone", "phones")

    private fun fetchDevicesByRole(role: String, jsonKey: String): List<GatewayReceiverDevice> {
        val url = "${gatewayBaseUrl()}/api/devices?role=$role"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyText = response.body?.string().orEmpty()
                val json = JSONObject(bodyText)
                val receivers = json.optJSONArray(jsonKey) ?: JSONArray()
                buildList {
                    for (i in 0 until receivers.length()) {
                        val item = receivers.getJSONObject(i)
                        add(
                            GatewayReceiverDevice(
                                id = item.getString("id"),
                                displayName = item.optString("displayName", "Device"),
                                passwordProtection = item.optBoolean("passwordProtection", false),
                                role = role
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AirReceiveGatewaySender", "Failed to fetch $role devices", e)
            emptyList()
        }
    }

    fun chunkUris(uris: List<Uri>): List<List<Uri>> {
        if (uris.isEmpty()) return emptyList()
        val chunks = mutableListOf<MutableList<Uri>>()
        var current = mutableListOf<Uri>()
        var currentBytes = 0L

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current)
                current = mutableListOf()
                currentBytes = 0L
            }
        }

        for (uri in uris) {
            val size = queryFileSize(uri)
            val wouldExceedBytes = current.isNotEmpty() &&
                size > 0 &&
                currentBytes + size > MAX_BATCH_BYTES
            val wouldExceedCount = current.size >= CHUNK_MAX_FILES
            if (wouldExceedBytes || wouldExceedCount) {
                flush()
            }
            current.add(uri)
            if (size > 0) currentBytes += size
        }
        flush()
        return chunks
    }

    fun uploadBatches(
        uris: List<Uri>,
        targetDeviceId: String? = null,
        uploadTarget: String = "receiver",
        sessionId: String? = null,
        uploadToken: String? = null,
        onTransferStarted: (label: String, totalSize: Long) -> Unit,
        onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onTransferCompleted: (photoCount: Int) -> Unit,
        onTransferFailed: (error: String) -> Unit
    ) {
        if (uris.isEmpty()) return
        val chunks = chunkUris(uris)
        val totalFiles = uris.size
        val knownTotal = uris.sumOf { queryFileSize(it).coerceAtLeast(0L) }
        val overallTotal = if (knownTotal > 0) knownTotal else -1L
        var bytesCompleted = 0L
        var filesCompleted = 0

        val startLabel = if (chunks.size > 1) {
            "Sending $totalFiles files (${chunks.size} batches)"
        } else {
            "Sending $totalFiles files"
        }
        onTransferStarted(startLabel, if (overallTotal > 0) overallTotal else totalFiles.toLong())

        for ((index, chunk) in chunks.withIndex()) {
            var failed = false
            var failureMessage = ""
            val chunkKnownTotal = chunk.sumOf { queryFileSize(it).coerceAtLeast(0L) }
            val chunkTotal = if (chunkKnownTotal > 0) chunkKnownTotal else -1L
            val chunkBaseBytes = bytesCompleted

            if (chunks.size > 1) {
                onTransferStarted(
                    "Sending $totalFiles files (batch ${index + 1}/${chunks.size})",
                    if (overallTotal > 0) overallTotal else totalFiles.toLong()
                )
            }

            uploadBatch(
                uris = chunk,
                targetDeviceId = targetDeviceId,
                uploadTarget = uploadTarget,
                sessionId = sessionId,
                uploadToken = uploadToken,
                onTransferStarted = { _, _ -> },
                onTransferProgress = { read, total ->
                    val overallRead = chunkBaseBytes + read
                    val overallTotalProgress = when {
                        overallTotal > 0 -> overallTotal
                        chunkTotal > 0 -> chunkBaseBytes + chunkTotal
                        else -> overallRead
                    }
                    onTransferProgress(overallRead, overallTotalProgress)
                },
                onTransferCompleted = { count ->
                    filesCompleted += count
                    bytesCompleted += if (chunkTotal > 0) chunkTotal else 0L
                },
                onTransferFailed = { error ->
                    failed = true
                    failureMessage = error
                }
            )

            if (failed) {
                onTransferFailed(failureMessage)
                return
            }
        }

        onTransferCompleted(filesCompleted)
    }

    fun requestTransferAuth(targetDeviceId: String?, senderLabel: String?): TransferAuthSession {
        return TransferAuthClient.requestAuth(client, gatewayBaseUrl(), targetDeviceId, senderLabel)
    }

    fun pollTransferAuth(sessionId: String): TransferAuthSession? {
        return TransferAuthClient.pollAuth(client, gatewayBaseUrl(), sessionId)
    }

    fun uploadBatch(
        uris: List<Uri>,
        targetDeviceId: String? = null,
        uploadTarget: String = "receiver",
        sessionId: String? = null,
        uploadToken: String? = null,
        onTransferStarted: (label: String, totalSize: Long) -> Unit,
        onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onTransferCompleted: (photoCount: Int) -> Unit,
        onTransferFailed: (error: String) -> Unit
    ) {
        if (uris.isEmpty()) return
        if (uris.size > CHUNK_MAX_FILES) {
            onTransferFailed("Internal error: chunk exceeds $CHUNK_MAX_FILES files.")
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
        val target = if (uploadTarget == "phone") "phone" else "receiver"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("target", target)
        if (!targetDeviceId.isNullOrBlank()) {
            multipart.addFormDataPart("targetDeviceId", targetDeviceId)
        }
        if (!sessionId.isNullOrBlank()) {
            multipart.addFormDataPart("sessionId", sessionId)
        }
        if (!uploadToken.isNullOrBlank()) {
            multipart.addFormDataPart("uploadToken", uploadToken)
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

        val requestBuilder = Request.Builder()
            .url(buildBatchUploadUrl())
            .post(multipart.build())
        if (!sessionId.isNullOrBlank()) {
            requestBuilder.header("X-Session-Id", sessionId)
        }
        if (!uploadToken.isNullOrBlank()) {
            requestBuilder.header("X-Upload-Token", uploadToken)
        }
        val request = requestBuilder.build()

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
                if (json.optBoolean("receiverRelayed", false) || json.optBoolean("phoneRelayed", false)) {
                    val count = json.optInt("count", uris.size)
                    Log.d("AirReceiveGatewaySender", "Batch relayed to $target: $count file(s)")
                    onTransferCompleted(count)
                } else {
                    val hint = if (target == "phone") {
                        "No Android receiver connected. Open AirReceive on the target phone, enable gateway in Settings, and keep the app in the foreground."
                    } else {
                        "No receive page connected. Open ${buildReceivePageUrl()} on the target device first."
                    }
                    onTransferFailed(hint)
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
