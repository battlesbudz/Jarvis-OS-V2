package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the owner at submission creation, even if a later turn replaces the engine's observer. */
internal class NativeInferenceBenchmark(
    private val modelId: String?,
    private val purpose: PipelineBenchmarkPurpose,
    private val warmState: PipelineBenchmarkWarmState,
    private val mode: String,
    private val audioBytes: Int = 0,
    private val imageBytes: Int = 0,
    private val initializationMs: Long? = null,
    private val sink: (PipelineBenchmarkSubmission) -> Unit = {},
    val measurement: NativeInferenceMeasurement = NativeInferenceMeasurement()
) {
    private val reported = AtomicBoolean(false)

    fun finish(
        outcome: PipelineBenchmarkOutcome,
        outputChars: Int,
        promptChars: Int,
        streamEvents: Int,
        prefillMs: Long? = null,
        prefillChunks: Int? = null,
        error: Throwable? = null,
        generationAttempted: Boolean = true
    ) {
        if (!reported.compareAndSet(false, true)) return
        val measured = measurement.snapshot(outputChars)
        val record = PipelineBenchmarkSubmission(
            submissionId = UUID.randomUUID().toString(),
            purpose = purpose,
            outcome = outcome,
            warmState = warmState,
            modelId = modelId,
            firstTokenMs = measured.firstRawTextMs.takeIf { generationAttempted },
            firstCallbackMs = measured.firstCallbackMs.takeIf { generationAttempted },
            totalGenerationMs = measured.durationMs.takeIf { generationAttempted },
            prefillMs = prefillMs,
            nativeSubmitMs = measured.nativeSubmitMs,
            promptCharacters = promptChars,
            // Android LiteRT-LM does not expose token IDs or the tokenizer's counts.
            exactInputTokens = null,
            exactOutputTokens = null,
            tokenTelemetrySource = "unavailable_litert_android_no_token_ids",
            estimatedOutputTokens = measured.estimatedOutputTokens.takeIf { generationAttempted },
            estimatedDecodeTokensPerSecond = measured.estimatedRawDecodeTokensPerSecond.takeIf { generationAttempted },
            streamEvents = streamEvents,
            measurements = linkedMapOf(
                "first_visible_text_ms" to measured.firstVisibleTextMs?.toDouble(),
                "native_terminal_ms" to measured.durationMs.toDouble().takeIf { generationAttempted },
                "consumer_return_ms" to measured.returnDurationMs.toDouble().takeIf { generationAttempted },
                "callback_count" to measured.callbackCount.toDouble(),
                "callback_interval_samples" to measured.callbackIntervalsRetained.toDouble(),
                "callback_interval_samples_dropped" to measured.callbackIntervalsDropped.toDouble(),
                "callback_interval_p50_ms" to measured.callbackIntervalP50Ms,
                "callback_interval_p95_ms" to measured.callbackIntervalP95Ms,
                "callback_interval_max_ms" to measured.callbackIntervalMaxMs,
                "visible_output_characters" to outputChars.toDouble(),
                "audio_bytes" to audioBytes.toDouble(),
                "image_bytes" to imageBytes.toDouble(),
                "engine_initialization_ms" to initializationMs?.toDouble(),
                "prefill_chunks" to prefillChunks?.toDouble()
            ),
            metadata = buildMap {
                put("input_mode", mode)
                put("generation_attempted", generationAttempted.toString())
                put("clock", "System.nanoTime")
                put("started_monotonic_ms", (measurement.beganAtNanos / 1_000_000).toString())
                put("estimated_tokens_policy", "ceil_visible_output_characters_div_4")
                put("estimated_decode_policy", "visible_char4_estimate_over_first_raw_text_to_native_terminal")
                put("ttft_policy", "first_nonempty_native_text_callback_including_hidden_or_control_text")
                put("callback_intervals_policy", "native_callback_chunks_nearest_rank_prefix_cap_4096")
                put("warm_state_policy", "engine_instance_first_submission_not_disk_cache")
                put("initialization_scope", "engine_initialize_wall_including_lock_wait_excluding_engine_constructor")
                error?.let { put("error_type", it.javaClass.simpleName) }
            }
        )
        // A diagnostics consumer must never break generation or cancellation cleanup.
        runCatching { sink(record) }
    }
}
