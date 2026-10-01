package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject

/** Exported definitions version independently from the backwards-compatible record schema. */
object PipelineBenchmarkDefinitions {
    val notes = linkedMapOf(
        "speech_end_to_first_answer_playback_ms" to "Last detected user speech to first answer playback-head advancement. Excludes filler; acoustic onset unavailable.",
        "endpoint_to_first_answer_text_ms" to "Recognition finalized to first answer text event. Includes preparation/model work after endpoint.",
        "ttft_ms" to "Native submission start to first nonempty text callback; callback chunks may contain multiple tokens.",
        "native_first_output_token_ms" to "Native runtime first-output-token timing when exposed; requires telemetry source.",
        "exact_decode_tokens_per_second" to "(Native output token count minus one) / native first-token-to-completion seconds. Unavailable without both native counts and timing.",
        "estimated_decode_tokens_per_second" to "Visible character-derived token estimate over observed decode duration; not native token throughput.",
        "process_cpu_work_ms" to "Difference in Android process elapsed CPU time at turn boundaries. Process-wide, includes concurrent work; not model-only CPU or energy.",
        "process_pss_kib_before" to "Process proportional set size sampled at start; not model allocation or peak memory.",
        "process_pss_kib_after" to "Process proportional set size sampled at finish; not peak memory.",
        "resource_sampling_before_ms" to "Observed monotonic duration of bounded resource observation at turn start.",
        "resource_sampling_after_ms" to "Observed monotonic duration of bounded resource observation at turn finish.",
        "asr_wer" to "Word edit errors / user-verified reference words. Unavailable without a verified reference.",
        "asr_cer" to "Character edit errors / user-verified reference characters. Unavailable without a verified reference.",
        "tts_playback_starvation_ms" to "Observed gap while the playback queue had no supplied answer audio. Does not measure audible silence directly."
    )
    fun statuses(metrics: Map<String, Double?>) = JSONObject().also { result ->
        metrics.forEach { (name, value) ->
            val status = when { value == null -> "unavailable"; name.startsWith("estimated_") -> "estimated";
                name.startsWith("exact_") || name == "native_first_output_token_ms" -> "native";
                name.contains("playback") -> "playback_proxy"; else -> "observed" }
            result.put(name, benchmarkJson("status" to status,
                "reason" to if (value == null) "not_observed_in_this_attempt_or_not_exposed_by_runtime" else null))
        }
    }
    fun json() = JSONObject().put("version", 1).put("definitions", JSONObject(notes))
        .put("missingValue", "unavailable_not_observed_or_not_supported; never zero-filled")
        .put("sampling", "resource snapshots only at turn boundaries; no periodic per-token polling")
        .put("privacy", "metrics-only by default; no prompt, transcript, reference text or PCM")
}

/** Status is derived on export; duplicating it in each disk record wastes bounded storage. */
internal fun JSONObject.withoutDerivedMetricStatus(): JSONObject {
    remove("metricStatus")
    optJSONArray("submissions")?.let { submissions ->
        for (index in 0 until submissions.length()) submissions.getJSONObject(index).remove("metricStatus")
    }
    return this
}
