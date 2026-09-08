package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Captures one speech turn, with bounded idle pre-roll and no raw audio on disk. */
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val createDetector: () -> SpeechDetector,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val createTranscriber: (() -> StreamingTranscriber)? = null,
    private val onPartialTranscript: (String, ByteArray) -> Unit = { _, _ -> },
    private val onMetrics: (AsrCaptureMetrics, String) -> Unit = { _, _ -> },
    private val trailingSilenceMs: Long = VoiceCallPolicy.TURN_SILENCE_MS
) {
    private val pcm = ByteArrayOutputStream()
    private val preRoll = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 600)
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
    @Volatile private var endRequested = false
    fun finishNow() { endRequested = true }
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
            "trailingSilenceMs=$trailingSilenceMs initialSilenceTimeoutMs=$initialSilenceTimeoutMs maxTurnMs=25000")
        captureReadyMs = nowMs() - captureRequestedAt
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                input.chunks().collect { chunk ->
                    if (turnCompleted.isCompleted) return@collect
                    audioBytes += chunk.size
                    val signal = Pcm16Signal.measure(chunk)
                    val decision = activeDetector.accept(chunk)
                    val now = nowMs()
                    synchronized(pcm) {
                        if (hasSpeech) {
                            val remaining = MAX_TURN_BYTES - pcm.size()
                            pcm.write(chunk, 0, minOf(remaining, chunk.size))
                        } else {
                            preRoll.append(chunk)
                        }
                        if (decision.isSpeech) {
                            if (!hasSpeech) {
                                firstSpeechAt = now
                                pcm.write(preRoll.snapshot())
                                preRoll.clear()
                                log("speech_started vad=silero elapsedMs=${now - startedAt}")
                            }
                            hasSpeech = true
                            lastSpeechAt = now
                        }
                    }
                    // ASR receives every frame from microphone startup. VAD controls
                    // submission and endpointing, not whether initial words reach the recognizer.
                    val decodeStartedAt = nowMs()
                    val partial = transcriber?.accept(chunk)
                    val chunkDecodeMs = nowMs() - decodeStartedAt
                    decodeMs += chunkDecodeMs
                    maxDecodeChunkMs = maxOf(maxDecodeChunkMs, chunkDecodeMs)
                    if (hasSpeech && partial != null) publishPartial(partial)
                    var reason = when {
                        endRequested -> "explicit_stop"
                        hasSpeech && now - lastSpeechAt >= trailingSilenceMs -> "trailing_silence"
                        hasSpeech && synchronized(pcm) { pcm.size() >= MAX_TURN_BYTES } -> "max_turn_duration"
                        !hasSpeech && initialSilenceTimeoutMs != null && now - startedAt >= initialSilenceTimeoutMs -> "initial_silence"
                        else -> null
                    }
                    if (reason != null && !turnCompleted.isCompleted) {
                        val finalizeStartedAt = nowMs()
                        if (hasSpeech) {
                            finalTranscript = transcriber?.finish().orEmpty().trim()
                            if (transcriber != null && finalTranscript.isBlank()) {
                                emptyCandidates++
                                hasSpeech = false
                                // A new word may be starting in the final, not-yet-confirmed
                                // VAD frame. Replay the tail into the replacement recognizer.
                                val tail = synchronized(pcm) {
                                    val recorded = pcm.toByteArray()
                                    val retained = recorded.copyOfRange((recorded.size - 19_200).coerceAtLeast(0), recorded.size)
                                    pcm.reset()
                                    preRoll.clear()
                                    preRoll.append(retained)
                                    retained
                                }
                                firstSpeechAt = null
                                firstPartialAfterSpeechMs = null
                                lastPartial = ""
                                log("empty_speech_candidate ignored=true count=$emptyCandidates microphone=kept_open")
                                if (initialSilenceTimeoutMs == null || nowMs() - startedAt < initialSilenceTimeoutMs) {
                                    decodeMs += nowMs() - finalizeStartedAt
                                    // Finish seals an ASR stream, so replace only that stream/engine.
                                    // The microphone keeps buffering opening words during model reload.
                                    val previous = transcriber
                                    transcriber = null
                                    previous?.close()
                                    val reloadAt = nowMs()
                                    transcriber = createTranscriber?.invoke()
                                    if (tail.isNotEmpty()) transcriber?.accept(tail)
                                    modelLoadMs += nowMs() - reloadAt
                                    return@collect
                                }
                                reason = "initial_silence"
                            }
                            publishPartial(finalTranscript, isFinal = true)
                            log("asr_final chars=${finalTranscript.length}")
                        }
                        onMetrics(AsrCaptureMetrics(modelLoadMs, captureReadyMs, audioBytes / 32,
                            decodeMs, maxDecodeChunkMs, firstPartialAfterSpeechMs, partialUpdates,
                            nowMs() - finalizeStartedAt, reason, emptyCandidates), finalTranscript)
                        turnCompleted.complete(hasSpeech)
                        log("turn_endpoint reason=$reason elapsedMs=${now - startedAt} " +
                            "silenceMs=${now - lastSpeechAt} speechDetected=$hasSpeech")
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
                    log("capture_stopped speechDetected=$hasSpeech")
                }
            }
            synchronized(pcm) {
                WavEncoder.pcm16Mono(if (hasSpeech) pcm.toByteArray() else preRoll.snapshot(), input.sampleRateHz)
            }
        }
    }

    private fun publishPartial(text: String, isFinal: Boolean = false) {
        val partial = text.trim()
        if (partial.isNotBlank() && partial != lastPartial) {
            lastPartial = partial
            if (!isFinal) partialUpdates++
            if (!isFinal && firstPartialAfterSpeechMs == null) {
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
