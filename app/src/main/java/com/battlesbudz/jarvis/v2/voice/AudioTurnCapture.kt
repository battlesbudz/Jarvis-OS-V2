package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineStart

/** Captures one explicit user turn without writing raw audio to disk. */
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {}
) {
    private val pcm = ByteArrayOutputStream()
    private var collectionJob: Job? = null
    private var turnCompleted: CompletableDeferred<Boolean>? = null
    @Volatile private var speechDetected = false
    @Volatile private var lastSpeechAtMs = 0L
    private var captureStartedAtMs = 0L
    private var lastLevelLogAtMs = 0L
    @Volatile private var initialSilenceTimeoutMs: Long? = INITIAL_SILENCE_TIMEOUT_MS

    suspend fun start() {
        check(collectionJob?.isActive != true) { "Audio capture is already active." }
        pcm.reset()
        speechDetected = false
        lastSpeechAtMs = 0L
        captureStartedAtMs = nowMs()
        lastLevelLogAtMs = captureStartedAtMs
        turnCompleted = CompletableDeferred()
        log("capture_started trailingSilenceMs=$TRAILING_SILENCE_MS")
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            input.chunks().collect { chunk ->
                synchronized(pcm) { pcm.write(chunk) }
                val signal = Pcm16Signal.measure(chunk)
                val now = nowMs()
                if (signal.isSpeech) {
                    if (!speechDetected) log("speech_started elapsedMs=${now - captureStartedAtMs}")
                    speechDetected = true
                    lastSpeechAtMs = now
                }
                val silentLongEnough = if (speechDetected) {
                    now - lastSpeechAtMs >= TRAILING_SILENCE_MS
                } else {
                    initialSilenceTimeoutMs?.let { now - captureStartedAtMs >= it } == true
                }
                if (silentLongEnough && turnCompleted?.complete(speechDetected) == true) {
                    log("turn_endpoint reason=${if (speechDetected) "trailing_silence" else "initial_silence"} " +
                        "elapsedMs=${now - captureStartedAtMs} silenceMs=${now - lastSpeechAtMs}")
                } else if (now - lastLevelLogAtMs >= 1_000L && turnCompleted?.isCompleted == false) {
                    lastLevelLogAtMs = now
                    log("capture_level rms=${signal.rms.toInt()} peak=${signal.peak} " +
                        "speech=${signal.isSpeech} speechDetected=$speechDetected " +
                        "silenceMs=${if (speechDetected) now - lastSpeechAtMs else now - captureStartedAtMs}")
                }
            }
        }
        input.start()
    }

    /**
     * Waits for a natural end-of-turn. The first six seconds allow the user
     * to begin speaking; after speech begins, a shorter trailing pause ends
     * the turn. This keeps the microphone hands-free without sending empty
     * turns to Gemma.
     */
    suspend fun awaitTurnCompletion(initialSilenceTimeoutMs: Long? = INITIAL_SILENCE_TIMEOUT_MS): Boolean {
        val completion = requireNotNull(turnCompleted) { "Audio capture has not started." }
        this.initialSilenceTimeoutMs = initialSilenceTimeoutMs
        log("awaiting_turn initialSilenceTimeoutMs=$initialSilenceTimeoutMs")
        return completion.await()
    }

    suspend fun stop(): ByteArray {
        input.stop()
        collectionJob?.cancel()
        collectionJob?.join()
        collectionJob = null
        turnCompleted?.cancel()
        turnCompleted = null
        log("capture_stopped")
        return synchronized(pcm) {
            WavEncoder.pcm16Mono(pcm.toByteArray(), input.sampleRateHz)
        }
    }

    private companion object {
        const val INITIAL_SILENCE_TIMEOUT_MS = 6_000L
        const val TRAILING_SILENCE_MS = 1_200L
    }
}
