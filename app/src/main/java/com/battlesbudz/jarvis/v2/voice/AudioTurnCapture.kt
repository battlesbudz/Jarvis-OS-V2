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
    private val onPartialTranscript: (String, ByteArray) -> Unit = { _, _ -> }
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
    private val turnCompleted = CompletableDeferred<Boolean>()
    private var stopped = false
    @Volatile var hasSpeech: Boolean = false
        private set

    suspend fun start(initialSilenceTimeoutMs: Long? = 6_000L) = lifecycle.withLock {
        check(!stopped && collectionJob == null) { "Audio capture is already started or stopped." }
        check(input.sampleRateHz == 16_000 && input.channelCount == 1) { "VAD requires 16 kHz mono audio." }
        val activeDetector = createDetector()
        detector = activeDetector
        transcriber = createTranscriber?.invoke()
        val startedAt = nowMs()
        var lastSpeechAt = startedAt
        var lastLevelLogAt = startedAt
        log("capture_started vad=silero threshold=0.5 speechConfirmationMs=96 " +
            "trailingSilenceMs=1200 initialSilenceTimeoutMs=$initialSilenceTimeoutMs maxTurnMs=25000")
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                input.chunks().collect { chunk ->
                    if (turnCompleted.isCompleted) return@collect
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
                    val partial = transcriber?.accept(chunk)
                    if (hasSpeech && partial != null) publishPartial(partial)
                    val reason = when {
                        hasSpeech && now - lastSpeechAt >= 1_200L -> "trailing_silence"
                        hasSpeech && synchronized(pcm) { pcm.size() >= MAX_TURN_BYTES } -> "max_turn_duration"
                        !hasSpeech && initialSilenceTimeoutMs != null && now - startedAt >= initialSilenceTimeoutMs -> "initial_silence"
                        else -> null
                    }
                    if (reason != null && !turnCompleted.isCompleted) {
                        if (hasSpeech) {
                            finalTranscript = transcriber?.finish().orEmpty().trim()
                            publishPartial(finalTranscript)
                            log("asr_final chars=${finalTranscript.length}")
                        }
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
        input.start()
        log("capture_ready pcmStartupMs=300")
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

    private fun publishPartial(text: String) {
        val partial = text.trim()
        if (partial.isNotBlank() && partial != lastPartial) {
            lastPartial = partial
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
