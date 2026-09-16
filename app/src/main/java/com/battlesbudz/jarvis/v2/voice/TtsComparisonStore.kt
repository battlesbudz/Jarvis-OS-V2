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
    val speechFrames: Long? = null, val outputRoute: String? = null,
    val firstTextToPcmMs: Long? = null, val firstTextToPlaybackMs: Long? = null,
    val openingChars: Int? = null, val preparedSynthesisMs: Long = 0,
    val preparedOpeningReused: Boolean = false,
    val observedPlaybackStarvationMs: Long? = null, val sourcePcmSummary: String? = null,
    val pcmDelivery: String? = null
)

/** Comparable raw synthesis measurements, separate from Gemma and ASR latency. */
class TtsComparisonStore(private val preferences: SharedPreferences) {
    @Synchronized fun clearDiagnostics() { preferences.edit().remove("results").remove("benchmark_results").apply() }

    @Synchronized fun selectedEngine(): TtsEngine {
        val saved = preferences.getString("engine", null)
        val selected = TtsEngine.fromId(saved)
        if (saved != selected.id) preferences.edit().putString("engine", selected.id).apply()
        return selected
    }
    @Synchronized fun select(engine: TtsEngine) { preferences.edit().putString("engine", engine.id).apply() }
    @Synchronized fun callProfile(engine: TtsEngine): TtsBenchmarkProfile? {
        val id = preferences.getString("call_profile_${engine.id}", null) ?: return null
        return TtsBenchmarkProfile.selectableProfiles.firstOrNull {
            it.id == id
        }
    }
    @Synchronized fun setCallProfile(engine: TtsEngine, profile: TtsBenchmarkProfile?) {
        preferences.edit().putString("call_profile_${engine.id}", profile?.id).apply()
    }
    private fun read(key: String, limit: Int): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(limit)
    }.getOrDefault(emptyList())
    @Synchronized fun records(): List<JSONObject> =
        (read("results", 40) + read("benchmark_results", TtsBenchmarkProfile.historyLimit)).sortedBy { it.optLong("atMs") }

    @Synchronized fun add(engine: TtsEngine, source: String, sample: String, metrics: TtsSessionMetrics,
                          run: TtsBenchmarkRun? = null) {
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
            .put("first_text_to_pcm_ms", metrics.firstTextToPcmMs ?: JSONObject.NULL)
            .put("first_text_to_playback_ms", metrics.firstTextToPlaybackMs ?: JSONObject.NULL)
            .put("opening_target_chars", metrics.openingChars ?: JSONObject.NULL)
            .put("prepared_synthesis_ms", metrics.preparedSynthesisMs)
            .put("prepared_opening_reused", metrics.preparedOpeningReused)
            .put("observed_playback_starvation_ms", metrics.observedPlaybackStarvationMs ?: JSONObject.NULL)
            .put("source_pcm_summary", metrics.sourcePcmSummary ?: JSONObject.NULL)
            .put("pcm_delivery", metrics.pcmDelivery ?: JSONObject.NULL)
        if (run != null) {
            item.put("audio_file", run.audioFile ?: JSONObject.NULL)
                .put("suite_id", run.suiteId).put("profile_id", run.profile.id).put("pass", run.pass)
                .put("input_text", run.text).put("input_delivery", run.inputDelivery)
                .put("planned_submissions", run.submissions?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("provenance", JSONObject(run.provenance))
                .put("first_intelligible_word_ms", JSONObject.NULL)
                .put("intelligibility_assessment", "not_assessed")
                .put("synthesis_mode", when { run.profile.piperPassages -> "piper-bounded-passages"; run.profile.fullText -> "full-text-before-playback"; else -> "streamed-phrases" })
                .put("requested_playback_speed", run.profile.playbackSpeed.toDouble())
                .put("playback_speed_applied", kotlin.math.abs(metrics.playbackSpeed - run.profile.playbackSpeed) < 0.001f)
                .put("opening_target_chars", run.profile.openingChars ?: JSONObject.NULL)
                .put("startup_buffer_target_ms", 0)
                .put("thermal_status_start", run.thermalStart).put("thermal_status_end", run.thermalEnd)
                .put("thermal_limited", run.thermalStart >= 3 || run.thermalEnd >= 3)
                .put("effective_rtf", if (metrics.audioMs > 0)
                    metrics.synthesisMs.toDouble() * metrics.playbackSpeed / metrics.audioMs else JSONObject.NULL)
                .put("estimated_playback_audio_ms", if (metrics.playbackSpeed > 0)
                    (metrics.audioMs / metrics.playbackSpeed).toLong() else JSONObject.NULL)
        }
        // Keep two complete all-voice matrices. Calls cannot evict benchmark results.
        val key = if (run == null) "results" else "benchmark_results"
        val limit = if (run == null) 40 else TtsBenchmarkProfile.historyLimit
        val array = JSONArray().also { a -> (read(key, limit) + item).takeLast(limit).forEach(a::put) }
        preferences.edit().putString(key, array.toString()).apply()
    }

    /** Include saved results from this suite, even when the UI displays only its latest run. */
    @Synchronized fun suiteRecords(item: JSONObject): List<JSONObject> {
        val suite = item.optString("suite_id").takeIf { it.isNotBlank() && it != "null" }
            ?: return emptyList()
        return records().filter { it.optString("suite_id") == suite }
            .sortedWith(compareBy<JSONObject> { executionNumber(it) ?: Int.MAX_VALUE }
                .thenBy { it.optLong("atMs") })
    }

    @Synchronized fun suiteDiagnosticReport(item: JSONObject): String {
        val runs = suiteRecords(item)
        require(runs.isNotEmpty()) { "No saved suite for this run." }
        return buildString {
            appendLine("Jarvis OS V2 whole voice comparison suite diagnostics")
            appendLine("suite_id=${item.optString("suite_id")}")
            appendLine("saved_runs=${runs.size}")
            if (runs.all { it.optString("source") == "paul-isolation-v1" }) {
                val expected = 14 // Historical immutable Paul suite size; no retired engine is loaded.
                appendLine("expected_runs=$expected")
                appendLine("all_runs_retained=${runs.mapNotNull { executionNumber(it) }.toSet() == (1..expected).toSet()}")
            }
            appendLine("completed_runs=${runs.count { it.optBoolean("completed") }}")
            appendLine("Includes saved failures and partial results; audio files are not embedded.")
            for ((index, run) in runs.withIndex()) {
                appendLine()
                appendLine("===== RUN ${executionNumber(run) ?: (index + 1)} =====")
                appendLine(describe(run))
            }
        }.trimEnd()
    }

    @Synchronized fun snapshot() = records().joinToString("\n\n", transform = ::describe)
        .ifBlank { "No TTS comparisons yet." }

    companion object {
        private fun executionNumber(item: JSONObject): Int? {
            val suite = item.optString("suite_id")
            val file = item.optString("audio_file")
            return file.takeIf { it.startsWith("$suite-") && it.endsWith(".wav") }
                ?.removePrefix("$suite-")?.removeSuffix(".wav")?.toIntOrNull()
        }

        /** Export the immutable displayed text run, never global history or current UI settings. */
        fun diagnosticReport(item: JSONObject) = "Jarvis OS V2 single voice text-run diagnostics\n\n" + describe(item)

        fun describe(item: JSONObject) = buildString {
            appendLine("TTS ${TtsEngine.diagnosticLabel(item.optString("engine"))} atMs=${item.optLong("atMs")}")
            for (key in listOf("id", "suite_id", "profile_id", "paul_stability", "audio_file", "pass", "model", "source", "sample", "input_text",
                "input_delivery", "planned_submissions", "provenance", "first_intelligible_word_ms", "intelligibility_assessment", "synthesis_mode", "requested_playback_speed", "playback_speed_applied",
                "startup_buffer_target_ms", "thermal_status_start", "thermal_status_end", "thermal_limited",
                "effective_rtf", "estimated_playback_audio_ms", "completed", "load_ms", "first_phrase_synthesis_ms",
                "synthesis_ms", "raw_audio_ms", "rtf", "playback_speed", "estimated_supply_gap_ms",
                "playback_confirmed", "played_frames", "speech_frames", "output_route",
                "first_text_to_pcm_ms", "first_text_to_playback_ms", "opening_target_chars",
                "prepared_synthesis_ms", "prepared_opening_reused",
                "observed_playback_starvation_ms", "source_pcm_summary", "pcm_delivery",
                "queue_wait_ms", "underruns_including_drain", "phrases", "threads", "text_chars", "text_sha256", "error"))
                appendLine("$key=${if (item.isNull(key)) "unavailable" else item.opt(key)}")
        }.trimEnd()
    }
}
