package com.battlesbudz.jarvis.v2.diagnostics

import android.content.Context
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class ReplyAsrObservation(val metrics: AsrCaptureMetrics, val text: String,
    val speechEndedAtMs: Long?, val finalizedAtMs: Long)

internal data class ReplyCaptureBenchmarkResult(val outcome: PipelineBenchmarkOutcome, val failureCode: String?) {
    companion object {
        fun classify(result: CapturedVoiceTurn, confirmed: Boolean, reason: String?): ReplyCaptureBenchmarkResult {
            val outcome = when {
                result.recognitionIssue == "microphone_paused" -> PipelineBenchmarkOutcome.CANCELLED
                result.recognitionIssue != null -> PipelineBenchmarkOutcome.ERROR
                !result.audioIsComplete -> PipelineBenchmarkOutcome.REJECTED
                result.transcript.isNotBlank() || result.wav.isNotEmpty() -> PipelineBenchmarkOutcome.COMPLETE
                confirmed -> PipelineBenchmarkOutcome.REJECTED
                else -> PipelineBenchmarkOutcome.NO_SPEECH
            }
            val failureCode = when {
                result.recognitionIssue == "microphone_paused" -> "microphone_paused"
                result.recognitionIssue != null -> "recognition_issue"
                !result.audioIsComplete -> "capture_audio_incomplete"
                outcome == PipelineBenchmarkOutcome.REJECTED -> reason ?: "confirmed_capture_no_final_request"
                else -> null
            }
            return ReplyCaptureBenchmarkResult(outcome, failureCode)
        }
    }
}

/** One independent capture observation. Reply output/tool work is measured by its own owner. */
internal class ReplyCaptureBenchmark(
    private val applicationContext: Context,
    private val benchmarks: PipelineBenchmarks,
    private val store: AndroidPipelineBenchmarkStore,
    private val currentCallId: () -> String?,
    private val onFailure: (String) -> Unit,
) {
    suspend fun listenBenchmarkedReply(
        output: PiperVoiceOutput,
        asrDirectory: File?,
        onConfirmed: () -> Unit,
        asrEngine: AsrEngine = AsrEngine.MOONSHINE,
        onPartialTranscript: (String) -> Unit = {},
        trace: VoiceTurnTrace? = null,
        inputFactory: (suspend () -> AudioInput)? = null,
        modelSession: VoiceModelSession? = null,
        asrOnly: Boolean = false,
        recognitionEnabled: Boolean = true,
        outputProvider: () -> PiperVoiceOutput = { output },
        log: (String) -> Unit = {}
    ): CapturedVoiceTurn {
        val id = UUID.randomUUID().toString()
        val callId = currentCallId()
        val epoch = store.hypothesisEpoch()
        val benchmark = runCatching { benchmarks.create(id,
            if (asrOnly) "voice_followup_capture" else "voice_reply_capture", asr = if (recognitionEnabled) asrEngine else null) }.getOrNull()
        val lastAsr = AtomicReference<ReplyAsrObservation?>(null)
        val lastReadyAt = AtomicLong(0)
        val confirmed = AtomicBoolean(false)
        val readinessCount = AtomicInteger(0)
        val retries = AtomicInteger(0)
        val resultReason = AtomicReference<String?>(null)
        var outcome = PipelineBenchmarkOutcome.UNKNOWN
        var failureCode: String? = null
        var resultSpeechEnd: Long? = null
        runCatching {
            val profile = SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED
            benchmark?.configuration("benchmark_unit", "independent_capture_no_answer_generation")
            benchmark?.configuration("capture_role", if (asrOnly) "accepted_followup" else "reply_interruption")
            benchmark?.configuration("capture_profile_requested", profile.id)
            benchmark?.configuration("microphone_source_requested", if (inputFactory != null) "caller_managed_retained_recorder"
                else if (profile.communicationInput) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION")
            benchmark?.configuration("noise_suppression_requested", if (inputFactory != null) "caller_managed" else profile.noiseSuppression.toString())
            benchmark?.configuration("capture_source_provenance", if (inputFactory != null) "actual_retained_source_not_observed_by_wrapper" else "selected_profile_requested")
            benchmark?.configuration("asr_work_scope", if (recognitionEnabled) "verified_interruption_and_final_capture" else "disabled_keyword_vad_only")
            benchmark?.configuration("asr_snapshot_scope", "last_finalized_capture_attempt")
            benchmark?.configuration("preconfirmation_probe_work", "outside_capture_asr_metrics_not_measured_here")
        }
        try {
            val result = ReplyVoiceCapture(applicationContext) { line ->
                when {
                    line.startsWith("barge_listener_restarting") -> retries.incrementAndGet()
                    line.startsWith("barge_stop_complete") -> resultReason.set("keyword_control_consumed")
                    line.startsWith("barge_correction_discarded") -> resultReason.set("final_request_not_confirmed")
                    line.startsWith("barge_floor_handoff") -> resultReason.set("floor_handoff")
                    line.startsWith("barge_correction_timeout") -> resultReason.set("correction_timeout")
                    line.startsWith("barge_capture_paused") -> resultReason.set("microphone_paused")
                }
                log(line)
            }.listen(output, asrDirectory, onConfirmed = {
                confirmed.set(true)
                benchmark?.mark("interruption_confirmed")
                onConfirmed()
            }, asrEngine = asrEngine, onPartialTranscript = { text ->
                if (text.isNotBlank()) benchmark?.mark("listener_first_asr_partial")
                onPartialTranscript(text)
            }, trace = trace, inputFactory = inputFactory, modelSession = modelSession, asrOnly = asrOnly, recognitionEnabled = recognitionEnabled,
                outputProvider = outputProvider,
                onReady = {
                    readinessCount.incrementAndGet()
                    lastReadyAt.set(System.nanoTime() / 1_000_000)
                    benchmark?.mark("listener_first_microphone_ready")
                }, onMetrics = { metrics, rawText, speechEndMs ->
                    lastAsr.set(ReplyAsrObservation(metrics, rawText, speechEndMs, System.nanoTime() / 1_000_000))
                })
            benchmark?.configuration("result_utterance_id", result.utteranceId)
            benchmark?.configuration("result_captured_at_epoch_ms", result.capturedAtMs.toString())
            benchmark?.configuration("result_origin", result.origin.name)
            benchmark?.metric("capture_result_audio_complete", if (result.audioIsComplete) 1 else 0)
            benchmark?.metric("capture_result_transcript_characters", result.transcript.length)
            resultSpeechEnd = result.speechEndedAtMs
            val terminal = ReplyCaptureBenchmarkResult.classify(result, confirmed.get(), resultReason.get())
            outcome = terminal.outcome
            failureCode = terminal.failureCode
            return result
        } catch (cancelled: CancellationException) {
            outcome = PipelineBenchmarkOutcome.CANCELLED
            failureCode = "listener_cancelled"
            throw cancelled
        } catch (error: Throwable) {
            outcome = PipelineBenchmarkOutcome.ERROR
            failureCode = "listener_error_${error.javaClass.simpleName.take(80)}"
            throw error
        } finally {
            // ReplyVoiceCapture has joined its capture cleanup before this boundary, including cancellation.
            runCatching {
                lastReadyAt.get().takeIf { it != 0L }?.let { benchmark?.markAt("microphone_ready", it) }
                lastAsr.get()?.let {
                    benchmark?.markAt("recognition_finalized", it.finalizedAtMs)
                    it.speechEndedAtMs?.let { speechEnd -> benchmark?.markAt("speech_ended", speechEnd) }
                    if (recognitionEnabled) benchmark?.recordReplyCaptureAsr(asrEngine, it.metrics, it.text.length)
                    else benchmark?.metric("audio_capture_ms", it.metrics.audioMs)
                }
                if (lastAsr.get()?.speechEndedAtMs == null) resultSpeechEnd?.let { benchmark?.markAt("speech_ended", it) }
                benchmark?.metric("listener_ready_attempts", readinessCount.get())
                benchmark?.metric("listener_recovery_retries", retries.get())
                benchmark?.metric("interruption_confirmed", if (confirmed.get()) 1 else 0)
                benchmark?.mark("capture_consumer_released")
                benchmark?.let { benchmarks.finishResources(it) }
                benchmark?.finish(outcome, callId, failureCode)?.let {
                    store.append(it, if (recognitionEnabled) lastAsr.get()?.text else null, expectedHypothesisEpoch = epoch)
                }
            }.onFailure { onFailure("Reply capture benchmark failed: ${it.javaClass.simpleName}") }
        }
    }
}

/** Busy worker timing is distinct from accept wall time and from the microphone's elapsed audio. */
private fun PipelineBenchmarkCapture.recordReplyCaptureAsr(engine: AsrEngine, metrics: AsrCaptureMetrics, characters: Int) {
    val work = metrics.recognitionWork
    asr(PipelineBenchmarkAsr(engine.id, loadMs = metrics.modelLoadMs, captureReadyMs = metrics.captureReadyMs,
        inputAudioMs = work?.takeIf { it.complete }?.submittedAudioMs,
        decodeWorkMs = work?.takeIf { it.complete }?.workMs,
        finalizationMs = metrics.finalizationMs, firstPartialMs = metrics.firstPartialAfterSpeechMs,
        endpointDelayMs = metrics.endpointDetectionMs?.let { it + metrics.finalizationMs },
        maxDecodeChunkMs = metrics.maxDecodeChunkMs, maxBacklogMs = metrics.maxRecognitionBacklogMs,
        partialUpdates = metrics.partialUpdates, transcriptCharacters = characters, endpointReason = metrics.endpointReason))
    metric("asr_capture_audio_ms", metrics.audioMs)
    metric("asr_capture_accept_wall_ms", metrics.decodeMs)
    metric("asr_max_recognition_work_ms", metrics.maxRecognitionWorkMs)
    metric("asr_final_decode_ms", metrics.finalDecodeMs)
    work?.let {
        configuration("asr_work_scope", it.scope)
        configuration("asr_work_complete", it.complete.toString())
        configuration("asr_submitted_audio_scope", "cumulative_pcm_per_api_submission_overlapping_retries_counted")
        metric("asr_worker_feed_ms", it.feedWorkMs)
        metric("asr_worker_partial_decode_ms", it.partialDecodeWorkMs)
        metric("asr_worker_final_decode_ms", it.finalDecodeWorkMs)
        metric("asr_worker_recovery_decode_ms", it.recoveryDecodeWorkMs)
        metric("asr_worker_invocations", it.invocations)
        metric("asr_worker_submitted_samples", it.submittedAudioSamples)
        metric("asr_realtime_factor", it.realtimeFactor)
    }
    metrics.acoustic?.let {
        metric("asr_sample_count", it.sampleCount)
        metric("asr_vad_admitted_sample_count", it.speechSampleCount)
        metric("asr_near_silent_sample_count", it.nearSilentSampleCount)
        metric("asr_clipped_sample_count", it.clippedSampleCount)
        metric("asr_rms_dbfs", it.rmsDbfs)
        metric("asr_peak_dbfs", it.peakDbfs)
        metric("asr_vad_admitted_rms_dbfs", it.speechRmsDbfs)
        metric("asr_vad_non_speech_rms_dbfs", it.nonSpeechRmsDbfs)
        metric("asr_vad_energy_contrast_db", it.speechToNonSpeechEnergyDb)
        metric("asr_noise_floor_rms", it.noiseFloorRms)
    }
}
