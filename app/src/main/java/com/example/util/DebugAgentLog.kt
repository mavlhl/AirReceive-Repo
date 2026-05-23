package com.example.util

import java.net.HttpURLConnection
import java.net.URL

object DebugAgentLog {
  private const val ENDPOINT = "http://127.0.0.1:7427/ingest/e9f47ee5-3ad4-471b-aaa3-2e53e96becd0"
  private const val SESSION = "c78bc5"

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
          val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
          conn.requestMethod = "POST"
          conn.setRequestProperty("Content-Type", "application/json")
          conn.setRequestProperty("X-Debug-Session-Id", SESSION)
          conn.doOutput = true
          conn.outputStream.use { it.write(payload.toByteArray()) }
          conn.responseCode
          conn.disconnect()
        } catch (_: Exception) {
          // Emulator may not reach host ingest; ignore
        }
      }
      .start()
  }
}
