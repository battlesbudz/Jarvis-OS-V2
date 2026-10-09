package com.battlesbudz.jarvis.v2.vision

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the on-device Roboflow-compatible inference server
 * (battlesbudz/roboflow-phone-inference, Termux, http://127.0.0.1:9001).
 *
 * Proof-of-connection slice: object detection only. The request/response
 * contract mirrors test_all.py in the phone-inference repo.
 */
data class DetectedObject(val label: String, val confidence: Double)

class PhoneVisionClient(
    private val baseUrl: String = "http://127.0.0.1:9001"
) {
    /** Returns detections plus the server round-trip time in ms. */
    suspend fun detectObjects(imageBytes: ByteArray, maxDetections: Int = 5): Pair<List<DetectedObject>, Long> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put(
                    "image", JSONObject()
                        .put("type", "base64")
                        .put("value", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                )
                .put("max_detections", maxDetections)
                .toString()
            val startedAt = System.currentTimeMillis()
            val conn = (URL("$baseUrl/infer/object_detection").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val detail = conn.errorStream?.bufferedReader()?.readText()?.take(300).orEmpty()
                    throw IllegalStateException("Vision server returned HTTP $code. $detail".trim())
                }
                val responseText = conn.inputStream.bufferedReader().readText()
                val elapsedMs = System.currentTimeMillis() - startedAt
                val predictions = JSONObject(responseText).optJSONArray("predictions")
                val objects = mutableListOf<DetectedObject>()
                if (predictions != null) {
                    for (i in 0 until predictions.length()) {
                        val item = predictions.optJSONObject(i) ?: continue
                        objects += DetectedObject(
                            label = item.optString("class", "?"),
                            confidence = item.optDouble("confidence", 0.0)
                        )
                    }
                }
                objects to elapsedMs
            } finally {
                conn.disconnect()
            }
        }

    /** True when the Termux server answers on /health. */
    suspend fun isServerUp(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            try {
                conn.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
