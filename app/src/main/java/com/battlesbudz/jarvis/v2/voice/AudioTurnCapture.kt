package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Captures one explicit user turn without writing raw audio to disk. */
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope
) {
    private val pcm = ByteArrayOutputStream()
    private var collectionJob: Job? = null
    private var turnCompleted: CompletableDeferred<Boolean>? = null
    @Volatile private var speechDetected = false
    @Volatile private var lastSpeechAtMs = 0L
    private var captureStartedAtMs = 0L
    @Volatile private var initialSilenceTimeoutMs: Long? = INITIAL_SILENCE_TIMEOUT_MS

    suspend fun start() {
        check(collectionJob?.isActive != true) { "Audio capture is already active." }
        pcm.reset()
        speechDetected = false
        lastSpeechAtMs = 0L
        captureStartedAtMs = System.currentTimeMillis()
        turnCompleted = CompletableDeferred()
        collectionJob = scope.launch {
            input.chunks().collect { chunk ->
                synchronized(pcm) { pcm.write(chunk) }
                val signal = speechSignal(chunk)
                val now = System.currentTimeMillis()
                if (signal) {
                    speechDetected = true
                    lastSpeechAtMs = now
                }
                val silentLongEnough = if (speechDetected) {
                    now - lastSpeechAtMs >= TRAILING_SILENCE_MS
                } else {
                    initialSilenceTimeoutMs?.let { now - captureStartedAtMs >= it } == true
                }
                if (silentLongEnough) {
                    turnCompleted?.complete(speechDetected)
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
        return completion.await()
    }

    suspend fun stop(): ByteArray {
        input.stop()
        collectionJob?.cancel()
        collectionJob?.join()
        collectionJob = null
        turnCompleted = null
        return synchronized(pcm) {
            WavEncoder.pcm16Mono(pcm.toByteArray(), input.sampleRateHz)
        }
    }

    private fun speechSignal(chunk: ByteArray): Boolean {
        if (chunk.size < 2) return false
        var sumSquares = 0.0
        var peak = 0
        var activeSamples = 0
        var samples = 0
        var offset = 0
        while (offset + 1 < chunk.size) {
            val raw = (chunk[offset].toInt() and 0xff) or (chunk[offset + 1].toInt() shl 8)
            val sample = if (raw and 0x8000 != 0) raw - 0x10000 else raw
            val magnitude = abs(sample)
            sumSquares += sample.toDouble() * sample.toDouble()
            peak = maxOf(peak, magnitude)
            if (magnitude >= SPEECH_SAMPLE_THRESHOLD) activeSamples++
            samples++
            offset += 2
        }
        if (samples == 0) return false
        val rms = sqrt(sumSquares / samples)
        val activeRatio = activeSamples.toDouble() / samples
        return rms >= SPEECH_RMS_THRESHOLD || (peak >= SPEECH_PEAK_THRESHOLD && activeRatio >= 0.01)
    }

    private companion object {
        const val INITIAL_SILENCE_TIMEOUT_MS = 6_000L
        const val TRAILING_SILENCE_MS = 1_200L
        const val SPEECH_SAMPLE_THRESHOLD = 500
        const val SPEECH_RMS_THRESHOLD = 500.0
        const val SPEECH_PEAK_THRESHOLD = 1_400
    }
}
