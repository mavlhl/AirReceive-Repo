package com.example.server

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class TransferAuthSession(
    val sessionId: String,
    val uploadToken: String,
    val pin: String? = null,
    val passwordRequired: Boolean = false
)

object TransferAuthClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class PendingAuth(
        val sessionId: String,
        val senderLabel: String?
    )

    fun fetchGatewayPending(client: OkHttpClient, baseUrl: String, deviceId: String): PendingAuth? {
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/transfer/pending/${deviceId}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" })
            val pending = json.optJSONArray("pending") ?: return null
            if (pending.length() == 0) return null
            val first = pending.getJSONObject(0)
            val sessionId = first.optString("sessionId").ifEmpty { return null }
            return PendingAuth(
                sessionId = sessionId,
                senderLabel = first.optString("senderLabel").ifEmpty { null }
            )
        }
    }

    fun fetchLocalPending(client: OkHttpClient, baseUrl: String): PendingAuth? {
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/transfer/pending")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" })
            val pending = json.optJSONArray("pending") ?: return null
            if (pending.length() == 0) return null
            val first = pending.getJSONObject(0)
            val sessionId = first.optString("sessionId").ifEmpty { return null }
            return PendingAuth(
                sessionId = sessionId,
                senderLabel = first.optString("senderLabel").ifEmpty { null }
            )
        }
    }

    fun requestAuth(
        client: OkHttpClient,
        baseUrl: String,
        targetDeviceId: String?,
        senderLabel: String?,
        senderDeviceId: String? = null
    ): TransferAuthSession {
        val body = JSONObject().apply {
            if (!targetDeviceId.isNullOrBlank()) put("targetDeviceId", targetDeviceId)
            if (!senderLabel.isNullOrBlank()) put("senderLabel", senderLabel)
            if (!senderDeviceId.isNullOrBlank()) put("senderDeviceId", senderDeviceId)
        }
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/transfer/request")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = JSONObject(text.ifEmpty { "{}" })
            if (!response.isSuccessful) {
                throw IOException(json.optString("error", "Authorization request failed (${response.code})"))
            }
            val pin = json.optString("pin").ifEmpty { null }
            val uploadToken = json.optString("uploadToken").ifEmpty { null }
            val passwordRequired = json.optBoolean("passwordRequired", false) ||
                (!pin.isNullOrBlank() && uploadToken.isNullOrBlank())
            return TransferAuthSession(
                sessionId = json.getString("sessionId"),
                uploadToken = uploadToken.orEmpty(),
                pin = pin,
                passwordRequired = passwordRequired
            )
        }
    }

    fun pollAuth(client: OkHttpClient, baseUrl: String, sessionId: String): TransferAuthSession? {
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/transfer/${sessionId}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" })
            return when (json.optString("status")) {
                "approved" -> TransferAuthSession(
                    sessionId = sessionId,
                    uploadToken = json.getString("uploadToken"),
                    passwordRequired = true
                )
                "expired" -> throw IOException("Transfer code expired. Try again.")
                else -> null
            }
        }
    }

    fun verifyPin(
        client: OkHttpClient,
        baseUrl: String,
        sessionId: String,
        pin: String,
        targetDeviceId: String?
    ) {
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("pin", pin)
            if (!targetDeviceId.isNullOrBlank()) put("targetDeviceId", targetDeviceId)
        }
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/transfer/verify")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" })
            if (!response.isSuccessful) {
                throw IOException(json.optString("error", "Incorrect code"))
            }
        }
    }

    fun fetchLocalPasswordProtection(client: OkHttpClient, baseUrl: String): Boolean {
        val request = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}/api/status")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val json = JSONObject(response.body?.string().orEmpty())
                json.optBoolean("passwordProtection", false)
            }
        } catch (_: Exception) {
            false
        }
    }
}
