package com.example.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object DebugAgentLog {
    private const val TAG = "AirReceiveDebug"
    private const val SESSION_ID = "180950"
    private const val INGEST_URL = "http://10.0.2.2:7427/ingest/e9f47ee5-3ad4-471b-aaa3-2e53e96becd0"

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(
        location: String,
        message: String,
        hypothesisId: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "pre-fix"
    ) {
        val payload = JSONObject().apply {
            put("sessionId", SESSION_ID)
            put("timestamp", System.currentTimeMillis())
            put("location", location)
            put("message", message)
            put("hypothesisId", hypothesisId)
            put("runId", runId)
            put("data", JSONObject(data))
        }
        Log.d(TAG, payload.toString())
        scope.launch {
            try {
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(INGEST_URL)
                    .post(body)
                    .header("X-Debug-Session-Id", SESSION_ID)
                    .build()
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // Emulator/host ingest optional; Logcat remains source of truth
            }
        }
    }
}
