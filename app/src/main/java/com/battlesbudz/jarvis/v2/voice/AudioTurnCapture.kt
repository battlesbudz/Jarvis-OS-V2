package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Captures one speech turn, with bounded idle pre-roll and no raw audio on disk. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val createDetector: () -> SpeechDetector,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val createTranscriber: (() -> StreamingTranscriber)? = null,
    private val onPartialTranscript: (String, ByteArray) -> Unit = { _, _ -> },
    private val onMetrics: (AsrCaptureMetrics, String) -> Unit = { _, _ -> },
    private val trailingSilenceMs: Long? = null,
    private val onRecognitionRecovery: (Boolean) -> Unit = {},
    private val allowAudioOnlyTurns: Boolean = false,
    private val onSpeechResumed: () -> Unit = {},
    private val acceptCandidate: (ByteArray) -> Boolean = { true },
    private val onAcceptedCandidate: (String) -> Unit = {}
) {
    private val pcm = ByteArrayOutputStream()
    private val speakerPcm = ByteArrayOutputStream()
    private val preRoll = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 1200)
    private val recoveryAudio = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 25_000)
    private val lifecycle = Mutex()
    private var collectionJob: Job? = null
    private var detector: SpeechDetector? = null
    private var transcriber: StreamingTranscriber? = null
    @Volatile var finalTranscript: String = ""
        private set
    private var lastPartial = ""
    private var partialUpdates = 0
    private var firstSpeechAt: Long? = null
    private var firstPartialAfterSpeechMs: Long? = null
    private var captureReadyMs = 0L
    private val turnCompleted = CompletableDeferred<Boolean>()
    private var stopped = false
    private val turnEnd = AdaptiveTurnEnd()
    private val quietEvidence = QuietSpeechEvidence()
    @Volatile var lastSpeechAtMs: Long? = null
        private set
    @Volatile private var endRequested = false
    fun finishNow() { endRequested = true }
    fun yieldMicrophone() { turnCompleted.completeExceptionally(MicrophoneBusyException()) }
    @Volatile var hasSpeech: Boolean = false
        private set

    suspend fun start(initialSilenceTimeoutMs: Long? = VoiceCallPolicy.CALL_INACTIVITY_MS) = lifecycle.withLock {
        check(!stopped && collectionJob == null) { "Audio capture is already started or stopped." }
        check(input.sampleRateHz == 16_000 && input.channelCount == 1) { "VAD requires 16 kHz mono audio." }
        // Start the hardware first; AndroidAudioInput buffers PCM even without a collector.
        // Model construction must not erase speech spoken during microphone preparation.
        val captureRequestedAt = nowMs()
        input.start()
        val activeDetector = createDetector()
        detector = activeDetector
        val loadStartedAt = nowMs()
        transcriber = createTranscriber?.invoke()
        var modelLoadMs = nowMs() - loadStartedAt
        val startedAt = nowMs()
        var audioBytes = 0L
        var decodeMs = 0L
        var maxDecodeChunkMs = 0L
        var emptyCandidates = 0
        var lastSpeechAt = startedAt
        var lastLevelLogAt = startedAt
        log("capture_started vad=silero threshold=0.5 speechConfirmationMs=96 " +
            "endpointing=${if (trailingSilenceMs == null) "adaptive" else "fixed"} " +
            "trailingSilenceMs=$trailingSilenceMs initialSilenceTimeoutMs=$initialSilenceTimeoutMs maxTurnMs=25000")
        captureReadyMs = nowMs() - captureRequestedAt
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                input.chunks().transformWhile { emit(it); !turnCompleted.isCompleted }.collect { chunk ->
                    if (turnCompleted.isCompleted) return@collect
                    audioBytes += chunk.size
                    recoveryAudio.append(chunk)
                    val signal = Pcm16Signal.measure(chunk)
                    val decision = activeDetector.accept(chunk)
                    val now = nowMs()
                    val audioAt = input.lastChunkCaptureTimeMs ?: now
                    if (decision.isSpeech && speakerPcm.size() < MAX_TURN_BYTES) {
                        speakerPcm.write(chunk, 0, minOf(chunk.size, MAX_TURN_BYTES - speakerPcm.size()))
                    }
                    synchronized(pcm) {
                        if (hasSpeech) {
                            val remaining = MAX_TURN_BYTES - pcm.size()
                            pcm.write(chunk, 0, minOf(remaining, chunk.size))
                        } else {
                            preRoll.append(chunk)
                        }
                        if (decision.isSpeech || (hasSpeech && decision.probability >= 0.5f)) {
                            if (hasSpeech && audioAt - lastSpeechAt >= 180) onSpeechResumed()
                            if (!hasSpeech) {
                                firstSpeechAt = now
                                pcm.write(preRoll.snapshot())
                                preRoll.clear()
                                log("speech_started vad=silero elapsedMs=${now - startedAt}")
                            }
                            hasSpeech = true
                            lastSpeechAt = audioAt
                            lastSpeechAtMs = audioAt
                        }
                    }
                    // ASR receives every frame from microphone startup. VAD controls
                    // submission and endpointing, not whether initial words reach the recognizer.
                    val decodeStartedAt = nowMs()
                    transcriber?.observeSpeech(hasSpeech && decision.probability >= 0.15f)
                    val partial = transcriber?.accept(chunk)?.let { if (TranscriptContent.isSoundOnly(it)) "" else TranscriptContent.speech(it) }
                    if (turnCompleted.isCompleted) return@collect
                    val chunkDecodeMs = nowMs() - decodeStartedAt
                    decodeMs += chunkDecodeMs
                    maxDecodeChunkMs = maxOf(maxDecodeChunkMs, chunkDecodeMs)
                    // Stable words corroborate weak whisper VAD; blank/noisy audio cannot
                    // qualify on amplitude alone. Strong VAD retains its existing fast path.
                    val corroborated = quietEvidence.accept(partial.orEmpty(), decision.probability, nowMs(), hasSpeech)
                    if (hasSpeech && corroborated) {
                        if (audioAt - lastSpeechAt >= 180) onSpeechResumed()
                        lastSpeechAt = audioAt
                        lastSpeechAtMs = audioAt
                    }
                    if (!hasSpeech && corroborated) {
                        synchronized(pcm) {
                            firstSpeechAt = now
                            pcm.write(preRoll.snapshot())
                            preRoll.clear()
                            hasSpeech = true
                            lastSpeechAt = audioAt
                            lastSpeechAtMs = audioAt
                        }
                        log("speech_started source=asr_and_vad probability=${decision.probability} preRollMs=1200")
                    }
                    if (hasSpeech && partial != null) publishPartial(partial)
                    val endpoint = trailingSilenceMs?.let { AdaptiveTurnEnd.Decision(it, "fixed") }
                        ?: turnEnd.decision(now).let { decision ->
                            if (decision.cue == "no_transcript") decision.copy(silenceMs = transcriber?.noTextSilenceMs ?: decision.silenceMs) else decision
                        }
                    var reason = when {
                        endRequested -> "explicit_stop"
                        hasSpeech && audioAt - lastSpeechAt >= endpoint.silenceMs &&
                            input.bufferedAudioMs == 0L -> "trailing_silence"
                        hasSpeech && synchronized(pcm) { pcm.size() >= MAX_TURN_BYTES } -> "max_turn_duration"
                        !hasSpeech && initialSilenceTimeoutMs != null && now - lastSpeechAt >= initialSilenceTimeoutMs -> "initial_silence"
                        else -> null
                    }
                    if (reason != null && !turnCompleted.isCompleted) {
                        val finalizeStartedAt = nowMs()
                        if (hasSpeech && !acceptCandidate(speakerPcm.toByteArray())) {
                            log("speaker_candidate_rejected microphone=kept_open elapsedMs=${now - startedAt}")
                            onSpeechResumed()
                            hasSpeech = false
                            finalTranscript = ""
                            synchronized(pcm) { pcm.reset(); preRoll.clear() }
                            speakerPcm.reset(); recoveryAudio.clear()
                            firstSpeechAt = null; firstPartialAfterSpeechMs = null; lastPartial = ""
                            quietEvidence.reset(); turnEnd.reset()
                            transcriber?.close(); transcriber = null
                            if (initialSilenceTimeoutMs != null && now - startedAt >= initialSilenceTimeoutMs) {
                                turnCompleted.complete(false)
                                return@collect
                            }
                            transcriber = createTranscriber?.invoke()
                            lastSpeechAt = startedAt
                            lastSpeechAtMs = null
                            return@collect
                        }
                        if (hasSpeech) {
                            val rawFinal = transcriber?.finish().orEmpty().trim()
                            val nonverbal = TranscriptContent.isSoundOnly(rawFinal)
                            finalTranscript = if (nonverbal) "" else TranscriptContent.speech(rawFinal)
                            if (nonverbal) log("nonverbal_candidate ignored=true destination=none microphone=kept_open")
                            if (transcriber != null && finalTranscript.isBlank() && !allowAudioOnlyTurns && !nonverbal) {
                                val candidate = recoveryAudio.snapshot()
                                val recoveryAt = nowMs()
                                log("asr_recovery_started candidateAudioMs=${candidate.size / 32} reason=empty_stream source=full_capture_window nativeVad=bypassed")
                                onRecognitionRecovery(true)
                                try {
                                    finalTranscript = TranscriptContent.speech(transcriber?.recover(candidate).orEmpty())
                                    log("asr_recovery_finished chars=${finalTranscript.length} elapsedMs=${nowMs() - recoveryAt}")
                                } finally {
                                    onRecognitionRecovery(false)
                                }
                            }
                            if (transcriber != null && finalTranscript.isBlank() && (!allowAudioOnlyTurns || nonverbal)) {
                                emptyCandidates++
                                hasSpeech = false
                                // A new word may be starting in the final, not-yet-confirmed
                                // VAD frame. Replay the tail into the replacement recognizer.
                                val tail = synchronized(pcm) {
                                    val recorded = pcm.toByteArray()
                                    val retained = recorded.copyOfRange((recorded.size - 38_400).coerceAtLeast(0), recorded.size)
                                    pcm.reset()
                                    preRoll.clear()
                                    preRoll.append(retained)
                                    retained
                                }
                                firstSpeechAt = null
                                firstPartialAfterSpeechMs = null
                                lastPartial = ""
                                turnEnd.reset()
                                speakerPcm.reset()
                                quietEvidence.reset()
                                onSpeechResumed()
                                log("empty_speech_candidate ignored=true count=$emptyCandidates microphone=kept_open inactivitySince=last_detected_speech")
                                if (initialSilenceTimeoutMs == null || nowMs() - (if (nonverbal) startedAt else lastSpeechAt) < initialSilenceTimeoutMs) {
                                    decodeMs += nowMs() - finalizeStartedAt
                                    // Finish seals an ASR stream, so replace only that stream/engine.
                                    // The microphone keeps buffering opening words during model reload.
                                    val previous = transcriber
                                    transcriber = null
                                    previous?.close()
                                    val reloadAt = nowMs()
                                    transcriber = createTranscriber?.invoke()
                                    recoveryAudio.clear()
                                    recoveryAudio.append(tail)
                                    if (tail.isNotEmpty()) transcriber?.accept(tail)
                                    modelLoadMs += nowMs() - reloadAt
                                    return@collect
                                }
                                reason = "initial_silence"
                            }
                            if (hasSpeech && finalTranscript.isBlank() && allowAudioOnlyTurns) {
                                log("audio_only_turn speechDetected=true destination=gemma")
                            }
                            // The owner seals against finalTranscript. Sending it as a new
                            // partial would cancel a matching draft immediately before seal.
                            if (hasSpeech) onAcceptedCandidate(finalTranscript)
                            log("asr_final chars=${finalTranscript.length}")
                        }
                        onMetrics(AsrCaptureMetrics(modelLoadMs, captureReadyMs, audioBytes / 32,
                            decodeMs, maxDecodeChunkMs, firstPartialAfterSpeechMs, partialUpdates,
                            nowMs() - finalizeStartedAt, reason, emptyCandidates,
                            lastSpeechAtMs?.let { (finalizeStartedAt - it).coerceAtLeast(0) },
                            endpoint.silenceMs, endpoint.cue), finalTranscript)
                        turnCompleted.complete(hasSpeech)
                        log("turn_endpoint reason=$reason elapsedMs=${now - startedAt} " +
                            "silenceMs=${audioAt - lastSpeechAt} endpointCue=${endpoint.cue} " +
                            "targetSilenceMs=${endpoint.silenceMs} speechDetected=$hasSpeech")
                    } else if (now - lastLevelLogAt >= 1_000L) {
                        lastLevelLogAt = now
                        log("capture_level vad=silero rms=${signal.rms.toInt()} peak=${signal.peak} " +
                            "probability=${decision.probability} speech=${decision.isSpeech} speechDetected=$hasSpeech " +
                            "silenceMs=${now - lastSpeechAt}")
                    }
                }
                if (!turnCompleted.isCompleted) {
                    turnCompleted.completeExceptionally(IllegalStateException("Microphone stream ended before the turn completed."))
                }
            } catch (cancelled: CancellationException) {
                turnCompleted.cancel()
                throw cancelled
            } catch (error: Throwable) {
                // Deliver model/stream failures to the owner, not the Activity's uncaught handler.
                turnCompleted.completeExceptionally(error)
            }
        }
        log("capture_ready pcmStartupMs=300 totalReadyMs=$captureReadyMs asrLoadMs=$modelLoadMs")
    }

    suspend fun awaitTurnCompletion(): Boolean = turnCompleted.await()

    suspend fun stop(): ByteArray = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!stopped) {
                stopped = true
                try {
                    input.stop()
                } finally {
                    collectionJob?.cancel()
                    collectionJob?.join()
                    collectionJob = null
                    turnCompleted.cancel()
                    try { transcriber?.close() } finally { detector?.close() }
                    transcriber = null
                    detector = null
                    recoveryAudio.clear()
                    speakerPcm.reset()
                    log("capture_stopped speechDetected=$hasSpeech")
                }
            }
            synchronized(pcm) {
                WavEncoder.pcm16Mono(if (hasSpeech) pcm.toByteArray() else preRoll.snapshot(), input.sampleRateHz)
            }
        }
    }

    private fun publishPartial(text: String) {
        val partial = text.trim()
        turnEnd.update(partial, nowMs())
        if (partial.isNotBlank() && partial != lastPartial) {
            lastPartial = partial
            partialUpdates++
            if (firstPartialAfterSpeechMs == null) {
                firstPartialAfterSpeechMs = firstSpeechAt?.let { (nowMs() - it).coerceAtLeast(0) }
            }
            log("asr_partial chars=${partial.length}")
            onPartialTranscript(partial, synchronized(pcm) {
                WavEncoder.pcm16Mono(pcm.toByteArray(), input.sampleRateHz)
            })
        }
    }

    private companion object {
        const val MAX_TURN_BYTES = 25 * 16_000 * 2
    }
}
