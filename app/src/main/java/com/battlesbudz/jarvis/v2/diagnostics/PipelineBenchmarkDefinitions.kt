package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject

/** Exported definitions version independently from the backwards-compatible record schema. */
object PipelineBenchmarkDefinitions {
    val notes = linkedMapOf(
        "speech_end_to_first_answer_playback_ms" to "Last detected user speech to first answer playback-head advancement. Excludes filler; acoustic onset unavailable.",
        "native_first_audio_to_speech_lower_ms" to "Lower clock-alignment bound: validated first native PCM admission to first answer playback-head event. Includes remaining capture; historical pre-roll is not backdated. Excludes filler; acoustic onset unmeasured.",
        "native_first_audio_to_speech_upper_ms" to "Upper clock-alignment bound for the same first-native-audio to answer-playback observation; not an acoustic timing accuracy bound.",
        "native_audio_first_step_from_admission_lower_ms" to "Lower elapsed bound from native PCM admission to first validated encoder graph-step completion; an append may only buffer and does not count as a step.",
        "native_audio_first_step_from_admission_upper_ms" to "Upper elapsed bound for first validated encoder-step completion, including clock alignment uncertainty.",
        "native_audio_endpoint_from_admission_lower_ms" to "Lower elapsed bound from native admission to the final accepted endpoint proposal, before recognizer finalization. Negative values mean admission was later than endpoint.",
        "native_audio_endpoint_from_admission_upper_ms" to "Upper elapsed bound for the accepted endpoint proposal relative to native admission.",
        "native_audio_steps_before_endpoint_lower_bound" to "Proven minimum validated step commits strictly before endpoint; a final snapshot spanning endpoint cannot establish an exact past count.",
        "native_audio_steps_before_endpoint_exact" to "Exact pre-endpoint count only when all observed steps precede endpoint or the first step is at/after it; otherwise unavailable.",
        "native_audio_rows_before_endpoint_exact" to "Validated post-adapter row count corresponding to an exact pre-endpoint step count; otherwise unavailable.",
        "native_audio_retained_pcm_samples" to "Complete final candidate's native-accepted 16 kHz mono PCM sample count, including retained pre-roll and trailing silence.",
        "native_audio_retained_preroll_samples" to "Actually retained onset buffer, including the confirming frame; not inferred from timestamp subtraction.",
        "native_audio_completed_steps" to "Total validated native encoder graph-step commits at final seal; includes any seal-only step.",
        "native_audio_validated_rows" to "Total validated post-adapter audio rows at final seal; excludes runtime end-of-audio marker.",
        "native_audio_clock_mapping_available" to "1 only with reviewed AOSP API30–36 same-rate source-family assumption and valid bounded calibration; installed/OEM binary not attested. 0 means mapped timing is unavailable.",
        "native_audio_alignment_uncertainty_ns" to "Ceiling of half the final Java-minus-native clock-offset interval width. Raw clocks/offsets are not exported.",
        "native_audio_checked_close_ms" to "Checked encoder close call to successful return; failed/quarantined owners cannot publish a final candidate timing record.",
        "native_audio_artifact_prepare_started" to "Stage offset before validating or reconstructing the pinned native encoder artifact; distinct from first PCM admission and inference.",
        "native_audio_artifact_prepare_finished" to "Stage offset after the verified artifact lease is registered with the turn; missing on preparation failure. The span includes cold reconstruction when required.",
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
            val status = when { value == null -> "unavailable";
                name.startsWith("native_first_audio_to_speech_") -> "playback_proxy_clock_bound";
                name.startsWith("estimated_") -> "estimated";
                name.startsWith("exact_") || name == "native_first_output_token_ms" -> "native";
                name.contains("playback") -> "playback_proxy"; else -> "observed" }
            result.put(name, benchmarkJson("status" to status,
                "reason" to if (value == null) "not_observed_in_this_attempt_or_not_exposed_by_runtime" else null))
        }
    }
    fun json() = JSONObject().put("version", 3).put("definitions", JSONObject(notes))
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
