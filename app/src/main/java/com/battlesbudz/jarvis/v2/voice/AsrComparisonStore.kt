package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Separate from the runtime event ring: comparisons survive playback, new calls and restarts. */
class AsrComparisonStore(private val preferences: SharedPreferences) {
    @Synchronized fun records(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString("turns", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(20)
    }.getOrDefault(emptyList())

    @Synchronized fun add(id: String, metrics: AsrCaptureMetrics, transcript: String) {
        val entry = JSONObject().put("id", id).put("atMs", System.currentTimeMillis())
            .put("engine", MoonshineModelInfo.id).put("model", MoonshineModelInfo.modelVersion)
            .put("transcript", transcript.take(4000)).put("model_load_ms", metrics.modelLoadMs)
            .put("capture_ready_ms", metrics.captureReadyMs).put("audio_fed_ms", metrics.audioMs)
            .put("decode_ms", metrics.decodeMs).put("max_decode_chunk_ms", metrics.maxDecodeChunkMs)
            .put("speech_detected_to_first_partial_ms", metrics.firstPartialAfterSpeechMs ?: JSONObject.NULL)
            .put("partial_updates", metrics.partialUpdates).put("finalization_ms", metrics.finalizationMs)
            .put("endpoint_reason", metrics.endpointReason).put("empty_candidates", metrics.emptyCandidates)
            .put("decode_realtime_factor", if (metrics.audioMs > 0)
                (metrics.decodeMs + metrics.finalizationMs).toDouble() / metrics.audioMs else JSONObject.NULL)
        save((records() + entry).takeLast(20))
    }

    @Synchronized fun update(id: String, key: String, value: Any) {
        val entries = records()
        entries.firstOrNull { it.optString("id") == id }?.put(key, value) ?: return
        save(entries)
    }

    @Synchronized fun setReference(id: String, reference: String) = update(id, "reference", reference.take(4000))

    @Synchronized fun snapshot(): String = records().joinToString("\n\n", transform = ::describe)
        .ifBlank { "No completed ASR measurements yet." }

    private fun save(entries: List<JSONObject>) {
        preferences.edit().putString("turns", JSONArray().also { a -> entries.forEach(a::put) }.toString()).apply()
    }

    companion object {
        private fun engineLabel(id: String) = when (id) {
            MoonshineModelInfo.id -> MoonshineModelInfo.label
            "zipformer" -> "Zipformer (retired)"
            else -> id.ifBlank { "Unknown recognizer" }
        }

        fun describe(entry: JSONObject): String = buildString {
            appendLine("ASR turn ${entry.optString("id")} atMs=${entry.optLong("atMs")}")
            appendLine("engine=${engineLabel(entry.optString("engine"))}")
            appendLine("model=${entry.optString("model")}")
            appendLine("transcript=${entry.optString("transcript")}")
            for (key in listOf("model_load_ms", "capture_ready_ms", "audio_fed_ms", "decode_ms",
                "decode_realtime_factor", "max_decode_chunk_ms", "speech_detected_to_first_partial_ms",
                "partial_updates", "empty_candidates", "finalization_ms", "endpoint_reason", "final_to_first_text_ms",
                "final_to_playback_start_ms", "prepared")) {
                appendLine("$key=${if (entry.has(key) && !entry.isNull(key)) entry.get(key) else "unavailable"}")
            }
            val reference = entry.optString("reference")
            val score = WordErrorRate.score(reference, entry.optString("transcript"))
            if (score == null) append("word_error_rate=unscored (reference required)")
            else {
                appendLine("reference=$reference")
                append("word_error_rate_percent=${String.format(Locale.ROOT, "%.1f", score.percent)} " +
                    "word_edits=${score.edits} reference_words=${score.referenceWords}")
            }
        }
    }
}
