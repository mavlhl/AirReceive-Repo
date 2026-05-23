package com.example.util

import java.net.HttpURLConnection
import java.net.URL

object DebugAgentLog {
  private val ENDPOINTS =
    listOf(
      "http://10.0.2.2:7427/ingest/e9f47ee5-3ad4-471b-aaa3-2e53e96becd0",
      "http://127.0.0.1:7427/ingest/e9f47ee5-3ad4-471b-aaa3-2e53e96becd0",
    )
  private const val SESSION = "c78bc5"
  private const val TAG = "DebugC78bc5"

  fun log(
    location: String,
    message: String,
    hypothesisId: String,
    data: Map<String, Any?> = emptyMap(),
    runId: String = "pre-fix",
  ) {
    Thread {
        try {
          val dataJson =
            data.entries.joinToString(",") { (k, v) ->
              "\"$k\":${when (v) {
                null -> "null"
                is Number -> v.toString()
                is Boolean -> v.toString()
                else -> "\"${v.toString().replace("\"", "\\\"")}\""
              }}"
            }
          val payload =
            """{"sessionId":"$SESSION","location":"$location","message":"$message","hypothesisId":"$hypothesisId","runId":"$runId","timestamp":${System.currentTimeMillis()},"data":{$dataJson}}"""
          android.util.Log.d(TAG, payload)
          var sent = false
          for (endpoint in ENDPOINTS) {
            if (sent) break
            try {
              val conn = URL(endpoint).openConnection() as HttpURLConnection
              conn.connectTimeout = 800
              conn.readTimeout = 800
              conn.requestMethod = "POST"
              conn.setRequestProperty("Content-Type", "application/json")
              conn.setRequestProperty("X-Debug-Session-Id", SESSION)
              conn.doOutput = true
              conn.outputStream.use { it.write(payload.toByteArray()) }
              if (conn.responseCode in 200..299) sent = true
              conn.disconnect()
            } catch (_: Exception) {
              // try next endpoint
            }
          }
        } catch (_: Exception) {
          // ignore
        }
      }
      .start()
  }
}
