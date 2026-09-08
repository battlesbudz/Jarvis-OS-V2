package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class TtsSessionMetrics(
    val loadMs: Long, val firstPcmMs: Long?, val synthesisMs: Long, val audioMs: Long,
    val queueWaitMs: Long, val playbackSpeed: Float, val supplyGapMs: Long,
    val underruns: Int, val phrases: Int, val textChars: Int, val textSha256: String,
    val threads: Int, val completed: Boolean, val error: String?,
    val playbackConfirmed: Boolean? = null, val playedFrames: Long? = null,
    val speechFrames: Long? = null, val outputRoute: String? = null
)

/** Comparable raw synthesis measurements, separate from Gemma and ASR latency. */
class TtsComparisonStore(private val preferences: SharedPreferences) {
    @Synchronized fun selectedEngine() = TtsEngine.fromId(preferences.getString("engine", null))
    @Synchronized fun select(engine: TtsEngine) { preferences.edit().putString("engine", engine.id).apply() }
    @Synchronized fun records(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString("results", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(40)
    }.getOrDefault(emptyList())

    @Synchronized fun add(engine: TtsEngine, source: String, sample: String, metrics: TtsSessionMetrics) {
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("atMs", System.currentTimeMillis())
            .put("engine", engine.id).put("model", engine.version).put("source", source).put("sample", sample)
            .put("load_ms", metrics.loadMs).put("first_phrase_synthesis_ms", metrics.firstPcmMs ?: JSONObject.NULL)
            .put("synthesis_ms", metrics.synthesisMs).put("raw_audio_ms", metrics.audioMs)
            .put("rtf", if (metrics.audioMs > 0) metrics.synthesisMs.toDouble() / metrics.audioMs else JSONObject.NULL)
            .put("queue_wait_ms", metrics.queueWaitMs).put("playback_speed", metrics.playbackSpeed.toDouble())
            .put("estimated_supply_gap_ms", metrics.supplyGapMs).put("underruns_including_drain", metrics.underruns)
            .put("phrases", metrics.phrases).put("text_chars", metrics.textChars).put("text_sha256", metrics.textSha256)
            .put("threads", metrics.threads).put("completed", metrics.completed).put("error", metrics.error ?: JSONObject.NULL)
        item.put("playback_confirmed", metrics.playbackConfirmed ?: JSONObject.NULL)
            .put("played_frames", metrics.playedFrames ?: JSONObject.NULL)
            .put("speech_frames", metrics.speechFrames ?: JSONObject.NULL)
            .put("output_route", metrics.outputRoute ?: JSONObject.NULL)
        val array = JSONArray().also { a -> (records() + item).takeLast(40).forEach(a::put) }
        preferences.edit().putString("results", array.toString()).apply()
    }

    @Synchronized fun snapshot() = records().joinToString("\n\n", transform = ::describe)
        .ifBlank { "No TTS comparisons yet." }

    companion object {
        fun describe(item: JSONObject) = buildString {
            appendLine("TTS ${TtsEngine.diagnosticLabel(item.optString("engine"))} atMs=${item.optLong("atMs")}")
            for (key in listOf("model", "source", "sample", "completed", "load_ms", "first_phrase_synthesis_ms",
                "synthesis_ms", "raw_audio_ms", "rtf", "playback_speed", "estimated_supply_gap_ms",
                "playback_confirmed", "played_frames", "speech_frames", "output_route",
                "queue_wait_ms", "underruns_including_drain", "phrases", "threads", "text_chars", "text_sha256", "error"))
                appendLine("$key=${if (item.isNull(key)) "unavailable" else item.opt(key)}")
        }.trimEnd()
    }
}
