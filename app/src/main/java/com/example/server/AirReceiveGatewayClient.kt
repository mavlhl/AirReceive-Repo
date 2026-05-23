package com.example.server

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class AirReceiveGatewayClient(
    private val context: Context,
    private val serverUrl: String,
    private val displayName: String,
    private val storedDeviceId: String? = null,
    private val onRegistered: ((deviceId: String, displayName: String) -> Unit)? = null,
    private val onTransferStarted: (fileName: String, fileSize: Long) -> Unit,
    private val onTransferProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    private val onTransferCompleted: (
        fileName: String,
        filePath: String,
        fileSize: Long,
        mimeType: String,
        senderIp: String
    ) -> Unit,
    private val onTransferFailed: (error: String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite timeout for long-lived WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var isRunning = false
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect() }

    fun start() {
        if (isRunning) return
        isRunning = true
        connect()
    }

    fun stop() {
        isRunning = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "App closed")
        webSocket = null
    }

    private fun connect() {
        if (!isRunning) return

        // Format gateway ws address
        val wsUrl = if (serverUrl.startsWith("https://")) {
            serverUrl.replace("https://", "wss://").removeSuffix("/") + "/ws/phone"
        } else if (serverUrl.startsWith("http://")) {
            serverUrl.replace("http://", "ws://").removeSuffix("/") + "/ws/phone"
        } else {
            "wss://" + serverUrl.removeSuffix("/") + "/ws/phone"
        }

        Log.d("AirReceiveGateway", "Connecting to WebSocket: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AirReceiveGateway", "WebSocket connection opened to gateway")
                val reg = JSONObject().apply {
                    put("type", "REGISTER")
                    put("displayName", displayName)
                    storedDeviceId?.let { put("deviceId", it) }
                }
                webSocket.send(reg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("AirReceiveGateway", "Received message: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "REGISTERED") {
                        val deviceId = json.optString("deviceId")
                        val name = json.optString("displayName")
                        Log.d("AirReceiveGateway", "Registered as $name ($deviceId)")
                        if (deviceId.isNotEmpty()) {
                            onRegistered?.invoke(deviceId, name)
                        }
                        return
                    }
                    if (type == "NOTIFY_UPLOAD") {
                        val fileId = json.getString("id")
                        val fileName = json.getString("name")
                        val fileSize = json.getLong("size")
                        val mimeType = json.optString("mimeType", "image/jpeg")

                        downloadFile(fileId, fileName, fileSize, mimeType)
                    }
                } catch (e: Exception) {
                    Log.e("AirReceiveGateway", "Error parsing WebSocket message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AirReceiveGateway", "WebSocket closed: $code / $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AirReceiveGateway", "WebSocket connection failure", t)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectHandler.postDelayed(reconnectRunnable, 5000) // retry in 5s
    }

    private fun downloadFile(fileId: String, fileName: String, fileSize: Long, mimeType: String) {
        // Construct GET download url
        val cleanUrl = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl.removeSuffix("/")
        } else {
            "https://" + serverUrl.removeSuffix("/")
        }
        val downloadUrl = "$cleanUrl/download/$fileId"

        Log.d("AirReceiveGateway", "Starting download: $downloadUrl")
        onTransferStarted(fileName, fileSize)

        val outputDir = File(context.filesDir, "received_photos")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        var targetFile = File(outputDir, fileName)
        if (targetFile.exists()) {
            val ext = targetFile.extension
            val baseName = targetFile.nameWithoutExtension
            targetFile = File(outputDir, "${baseName}_${System.currentTimeMillis()}.${ext}")
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AirReceiveGateway", "Failed to download relayed file", e)
                onTransferFailed(e.localizedMessage ?: "Download failed")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onTransferFailed("Server error during download: ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    onTransferFailed("Empty response body")
                    return
                }

                var fileOutputStream: FileOutputStream? = null
                try {
                    fileOutputStream = FileOutputStream(targetFile)
                    val inputStream = body.byteStream()
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        onTransferProgress(totalBytesRead, fileSize)
                    }

                    fileOutputStream.flush()
                    fileOutputStream.close()
                    fileOutputStream = null

                    Log.d("AirReceiveGateway", "Gateway download complete for: ${targetFile.name}")
                    onTransferCompleted(
                        targetFile.name,
                        targetFile.absolutePath,
                        totalBytesRead,
                        mimeType,
                        "Render Gateway"
                    )
                } catch (e: Exception) {
                    fileOutputStream?.close()
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    Log.e("AirReceiveGateway", "Error writing file", e)
                    onTransferFailed(e.localizedMessage ?: "File stream error")
                }
            }
        })
    }
}
