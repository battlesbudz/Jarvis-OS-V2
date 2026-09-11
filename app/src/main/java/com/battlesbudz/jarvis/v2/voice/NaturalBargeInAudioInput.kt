package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow

/** VAD-qualified bounded probes, alongside the keyword fallback, on one capture stream. */
class NaturalBargeInAudioInput(
    private val input: AudioInput,
    private val createKeyword: () -> InterruptionKeywordDetector,
    private val createVad: () -> SpeechDetector,
    private val createTranscriber: () -> StreamingTranscriber,
    private val playing: () -> Boolean,
    private val reference: () -> String,
    private val hasPlaybackBudget: () -> Boolean,
    private val onConfirmed: (Boolean, String) -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : AudioInput {
    companion object { const val MAX_PROBES = 4 }
    init { require(input.sampleRateHz == 16_000 && input.channelCount == 1) }
    override val sampleRateHz get() = input.sampleRateHz
    override val channelCount get() = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        supervisorScope {
            val started = nowMs()
            val keyword = createKeyword()
            val keywordLoadMs = (nowMs() - started).coerceAtLeast(0)
            var keywordBytes = 0L
            var maxKeywordWorkMs = 0L
            var maxBacklogMs = 0L
            var vad: SpeechDetector? = null
            val worker = BoundedInterruptionRecognizer(this, createTranscriber, nowMs, dispatcher = dispatcher, log = log, hasBudget = hasPlaybackBudget)
            val onset = RollingAudioBuffer(maxDurationMs = 400)
            val candidate = RollingAudioBuffer(maxDurationMs = 4000)
            var active = false
            var submitted = false
            var submittedBytes = 0L
            var candidateProbes = 0
            var delivered = false
            var disabled = false
            var revision = 0L
            var probes = 0
            var lastSpeechAt = 0L
            var candidateAt = 0L
            var cooldownUntil = 0L
            var hypothesis: BoundedInterruptionRecognizer.Result? = null
            var gate = BargeInGate()
            var keywordReady = false
            var backlogSuspended = false
            var recoveredAt: Long? = null
            var backlogRecoveries = 0
            var rejectedWindows = 0
            var staleResults = 0
            var pressureFrames = 0
            var finalReason = "no_confirmed_request"
            fun reset() {
                active = false; submitted = false; submittedBytes = 0; candidateProbes = 0; hypothesis = null
                candidate.clear(); gate = BargeInGate(); revision++
            }
            fun disable(reason: String) {
                if (!disabled) {
                    disabled = true; reset()
                    finalReason = reason
                    log("barge_natural_unavailable reason=$reason fallback=keyword")
                }
            }
            try {
                vad = try { createVad() } catch (error: Exception) {
                    disable("vad_load_${error.javaClass.simpleName}"); null
                }
                log("barge_capture_ready mode=bounded_candidates keywordReady=false naturalSpeechReady=false")
                input.chunks().collect { pcm ->
                    if (delivered) { emit(pcm); return@collect }
                    val now = nowMs()
                    val at = input.lastChunkCaptureTimeMs ?: now
                    val keywordAt = nowMs()
                    val hit = keyword.accept(pcm)
                    keywordBytes += pcm.size
                    maxKeywordWorkMs = maxOf(maxKeywordWorkMs, (nowMs() - keywordAt).coerceAtLeast(0))
                    maxBacklogMs = maxOf(maxBacklogMs, input.bufferedAudioMs)
                    if (!keywordReady && keyword.ready) {
                        keywordReady = true
                        log("barge_keyword_ready keywords=Hey_Jarvis,stop readyMs=${now - started} mode=bounded_candidates " +
                            "loadMs=$keywordLoadMs inputMs=${keywordBytes / 32} maxWorkMs=$maxKeywordWorkMs maxBacklogMs=$maxBacklogMs")
                    }
                    if (hit != null) {
                        finalReason = "keyword_$hit"
                        delivered = true
                        onConfirmed(false, hit) // Stop playback before waiting for native ownership.
                        worker.close()
                        log("barge_keyword_confirmed keyword=$hit")
                        return@collect // Consume the trigger, as on the existing keyword path.
                    }
                    worker.poll()?.let { result ->
                        if (result.revision == revision && now - result.audioAtMs <= InterruptionTiming.RESULT_AGE_MS) {
                            hypothesis = result
                            log("barge_natural_ready scope=candidate revision=$revision resultAgeMs=${now - result.audioAtMs}")
                        }
                        else {
                            staleResults++
                            log("barge_probe_discarded reason=stale revision=${result.revision}")
                        }
                    }
                    if (worker.unavailable) disable("recognizer_budget_or_failure")
                    if (disabled) return@collect
                    if (input.bufferedAudioMs > 600 || now - at > 800) {
                        if (!backlogSuspended) {
                            backlogSuspended = true; reset(); onset.clear()
                            log("barge_natural_suspended reason=capture_backlog retryable=true fallback=keyword")
                        }
                        recoveredAt = null
                    }
                    if (backlogSuspended) {
                        if (input.bufferedAudioMs <= 200 && now - at <= 300 && !worker.busy) {
                            if (recoveredAt == null) recoveredAt = now
                            if (now - requireNotNull(recoveredAt) >= InterruptionTiming.RECOVERY_QUIET_MS) {
                                backlogSuspended = false; backlogRecoveries++
                                log("barge_natural_recovered reason=capture_caught_up fresh_candidate_required=true")
                            }
                        } else recoveredAt = null
                        if (backlogSuspended) return@collect
                    }
                    if (submitted && !worker.busy && worker.retryableFailure) {
                        reset(); cooldownUntil = now + 500
                        log("barge_candidate_deferred reason=playback_budget retryAfterMs=500")
                    }
                    val speech = try { vad?.accept(pcm)?.isSpeech == true } catch (error: Exception) {
                        disable("vad_${error.javaClass.simpleName}"); false
                    }
                    if (disabled) return@collect
                    onset.append(pcm)
                    if (speech) lastSpeechAt = at
                    if (!active && speech && now >= cooldownUntil && !worker.busy) {
                        active = true; candidateAt = at; candidate.append(onset.snapshot())
                    } else if (active) candidate.append(pcm)
                    if (!active) return@collect
                    if (at - candidateAt >= 3400) {
                        // Never recognize a truncated candidate whose onset has rolled away.
                        reset(); cooldownUntil = now + 1000
                        rejectedWindows++
                        log("barge_candidate_rejected reason=window_limit playback_uninterrupted=true")
                        return@collect
                    }
                    val heard = hypothesis
                    if (heard != null && now - heard.audioAtMs <= InterruptionTiming.CONFIRM_AGE_MS &&
                        now - lastSpeechAt <= InterruptionTiming.CONFIRM_AGE_MS &&
                        gate.update(true, playing(), now, heard.text, reference()) == BargeInGate.Action.CONFIRM) {
                        delivered = true
                        finalReason = "natural_confirmed"
                        val echo = reference()
                        onConfirmed(true, echo)
                        worker.close() // Final-turn ASR must never overlap the probe lease.
                        log("barge_speech_confirmed method=bounded_candidate preRollMs=${candidate.sizeBytes() / 32}")
                        emit(candidate.snapshot())
                        candidate.clear(); onset.clear()
                    } else if ((heard != null && now - heard.audioAtMs > InterruptionTiming.CONFIRM_AGE_MS) ||
                        (heard == null && !worker.busy && at - lastSpeechAt > 800)) {
                        reset(); cooldownUntil = now + 500
                    }
                    if (delivered || !active) return@collect
                    if (!hasPlaybackBudget()) pressureFrames++
                    if ((!submitted || (hypothesis != null && gate.reason != "words_settling" && candidateProbes < 2 && candidate.sizeBytes() >= submittedBytes + 16_000)) &&
                        candidate.sizeBytes() >= 32_000 && hasPlaybackBudget()) {
                        if (probes >= MAX_PROBES) { disable("reply_probe_limit"); return@collect }
                        if (worker.submit(revision, candidate.snapshot(), at)) {
                            submitted = true; submittedBytes = candidate.sizeBytes(); candidateProbes++; probes++
                            hypothesis = null; gate = BargeInGate()
                            log("barge_probe_started revision=$revision preRollMs=${candidate.sizeBytes() / 32} attempt=$probes")
                        }
                    }
                }
            } finally {
                worker.close()
                try { vad?.close() } finally {
                    keyword.close(); onset.clear(); candidate.clear()
                    log("barge_keyword_summary ready=$keywordReady inputMs=${keywordBytes / 32} " +
                        "loadMs=$keywordLoadMs maxWorkMs=$maxKeywordWorkMs maxBacklogMs=$maxBacklogMs")
                    log("barge_natural_summary probes=$probes reason=$finalReason backlogRecoveries=$backlogRecoveries " +
                        "backlogSuspended=$backlogSuspended pressureFrames=$pressureFrames " +
                        "windowRejects=$rejectedWindows staleResults=$staleResults")
                }
            }
        }
    }
}
