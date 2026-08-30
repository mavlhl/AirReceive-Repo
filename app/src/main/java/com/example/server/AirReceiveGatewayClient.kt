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
    private var passwordProtection: Boolean = false,
    private val onRegistered: ((deviceId: String, displayName: String) -> Unit)? = null,
    private val onAuthRequired: ((sessionId: String, senderLabel: String?) -> Unit)? = null,
    private val onChatMessage: ((messageId: String, fromDeviceId: String, fromDisplayName: String, text: String, sentAt: Long) -> Unit)? = null,
    private val onChatSent: ((clientMessageId: String?, messageId: String, status: String) -> Unit)? = null,
    private val onChatError: ((error: String, clientMessageId: String?) -> Unit)? = null,
    private val onGlobalChatMessage: ((messageId: String, fromDeviceId: String, fromDisplayName: String, text: String, sentAt: Long) -> Unit)? = null,
    private val onGlobalChatSent: ((clientMessageId: String?, messageId: String) -> Unit)? = null,
    private val onGlobalChatError: ((error: String, clientMessageId: String?) -> Unit)? = null,
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

    fun setPasswordProtection(enabled: Boolean) {
        passwordProtection = enabled
        webSocket?.send(JSONObject().apply {
            put("type", "SET_PASSWORD_PROTECTION")
            put("passwordProtection", enabled)
        }.toString())
    }

    fun sendChatMessage(toDeviceId: String, text: String, clientMessageId: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "CHAT_SEND")
            put("toDeviceId", toDeviceId)
            put("text", text)
            put("clientMessageId", clientMessageId)
        }.toString())
    }

    fun sendGlobalChatMessage(text: String, clientMessageId: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "GLOBAL_CHAT_SEND")
            put("text", text)
            put("clientMessageId", clientMessageId)
        }.toString())
    }

    private fun pollPendingChat(deviceId: String) {
        val cleanUrl = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl.removeSuffix("/")
        } else {
            "https://" + serverUrl.removeSuffix("/")
        }
        val url = "$cleanUrl/api/chat/pending/${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("AirReceiveGateway", "Pending chat poll failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val bodyText = it.body?.string().orEmpty()
                    try {
                        val json = JSONObject(bodyText)
                        val messages = json.optJSONArray("messages") ?: return
                        for (i in 0 until messages.length()) {
                            val item = messages.getJSONObject(i)
                            onChatMessage?.invoke(
                                item.getString("messageId"),
                                item.getString("fromDeviceId"),
                                item.optString("fromDisplayName", "Device"),
                                item.getString("text"),
                                item.optLong("sentAt", System.currentTimeMillis())
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("AirReceiveGateway", "Error parsing pending chat", e)
                    }
                }
            }
        })
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
                    put("passwordProtection", passwordProtection)
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
                            pollPendingChat(deviceId)
                        }
                        return
                    }
                    if (type == "CHAT_MESSAGE") {
                        onChatMessage?.invoke(
                            json.getString("messageId"),
                            json.getString("fromDeviceId"),
                            json.optString("fromDisplayName", "Device"),
                            json.getString("text"),
                            json.optLong("sentAt", System.currentTimeMillis())
                        )
                        return
                    }
                    if (type == "CHAT_SENT") {
                        onChatSent?.invoke(
                            json.optString("clientMessageId").ifEmpty { null },
                            json.optString("messageId"),
                            json.optString("status", "delivered")
                        )
                        return
                    }
                    if (type == "CHAT_ERROR") {
                        onChatError?.invoke(
                            json.optString("error", "Chat error"),
                            json.optString("clientMessageId").ifEmpty { null }
                        )
                        return
                    }
                    if (type == "GLOBAL_CHAT_MESSAGE") {
                        onGlobalChatMessage?.invoke(
                            json.getString("messageId"),
                            json.getString("fromDeviceId"),
                            json.optString("fromDisplayName", "Device"),
                            json.getString("text"),
                            json.optLong("sentAt", System.currentTimeMillis())
                        )
                        return
                    }
                    if (type == "GLOBAL_CHAT_SENT") {
                        onGlobalChatSent?.invoke(
                            json.optString("clientMessageId").ifEmpty { null },
                            json.optString("messageId")
                        )
                        return
                    }
                    if (type == "GLOBAL_CHAT_ERROR") {
                        onGlobalChatError?.invoke(
                            json.optString("error", "Global chat error"),
                            json.optString("clientMessageId").ifEmpty { null }
                        )
                        return
                    }
                    if (type == "AUTH_REQUIRED") {
                        val sessionId = json.optString("sessionId")
                        val senderLabel = json.optString("senderLabel").ifEmpty { null }
                        if (sessionId.isNotEmpty()) {
                            onAuthRequired?.invoke(sessionId, senderLabel)
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
