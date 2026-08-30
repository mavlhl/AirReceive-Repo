package com.example.server

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalTransferAuth(
    private var passwordProtection: Boolean = false,
    private val onAuthRequired: ((sessionId: String, senderLabel: String?) -> Unit)? = null
) {
    data class Session(
        val sessionId: String,
        val pin: String?,
        var uploadToken: String?,
        var status: String,
        val expiresAt: Long,
        val senderLabel: String?
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun setPasswordProtection(enabled: Boolean) {
        passwordProtection = enabled
    }

    fun isPasswordProtectionEnabled(): Boolean = passwordProtection

    fun createRequest(senderLabel: String?): JSONObject {
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + TRANSFER_SESSION_TTL_MS
        if (!passwordProtection) {
            val uploadToken = UUID.randomUUID().toString()
            sessions[sessionId] = Session(
                sessionId = sessionId,
                pin = null,
                uploadToken = uploadToken,
                status = "approved",
                expiresAt = expiresAt,
                senderLabel = senderLabel
            )
            return JSONObject().apply {
                put("passwordRequired", false)
                put("sessionId", sessionId)
                put("uploadToken", uploadToken)
            }
        }
        val pin = generatePin()
        sessions[sessionId] = Session(
            sessionId = sessionId,
            pin = pin,
            uploadToken = null,
            status = "pending",
            expiresAt = expiresAt,
            senderLabel = senderLabel
        )
        onAuthRequired?.invoke(sessionId, senderLabel)
        return JSONObject().apply {
            put("passwordRequired", true)
            put("sessionId", sessionId)
            put("pin", pin)
        }
    }

    fun verifyPin(sessionId: String, pin: String): JSONObject {
        val session = sessions[sessionId] ?: return errorJson(404, "Session not found.")
        if (System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(sessionId)
            return errorJson(410, "Session expired.")
        }
        if (pin.trim() != session.pin) {
            return errorJson(401, "Incorrect code.")
        }
        session.status = "approved"
        session.uploadToken = UUID.randomUUID().toString()
        return JSONObject().apply {
            put("status", "approved")
            put("uploadToken", session.uploadToken)
        }
    }

    fun sessionStatus(sessionId: String): JSONObject {
        val session = sessions[sessionId] ?: return JSONObject().put("status", "expired")
        if (System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(sessionId)
            return JSONObject().put("status", "expired")
        }
        return JSONObject().apply {
            put("status", session.status)
            if (session.status == "approved" && session.uploadToken != null) {
                put("uploadToken", session.uploadToken)
            }
        }
    }

    fun validateUpload(sessionId: String?, uploadToken: String?): String? {
        if (!passwordProtection) return null
        val sid = sessionId?.trim().orEmpty()
        val token = uploadToken?.trim().orEmpty()
        if (sid.isEmpty() || token.isEmpty()) {
            return "Transfer password required. Request authorization first."
        }
        val session = sessions[sid] ?: return "Session expired or not found."
        if (System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(sid)
            return "Session expired."
        }
        if (session.status != "approved" || session.uploadToken != token) {
            return "Invalid or unapproved transfer session."
        }
        return null
    }

    fun statusJson(): JSONObject {
        return JSONObject().put("passwordProtection", passwordProtection)
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now > it.value.expiresAt }
    }

    private fun errorJson(code: Int, message: String): JSONObject {
        return JSONObject().put("error", message).put("code", code)
    }

    companion object {
        private const val TRANSFER_SESSION_TTL_MS = 5 * 60 * 1000L

        private fun generatePin(): String =
            (100000 + (Math.random() * 900000).toInt()).toString()
    }
}
