package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Captures one explicit user turn without writing raw audio to disk. */
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope
) {
    private val pcm = ByteArrayOutputStream()
    private var collectionJob: Job? = null

    suspend fun start() {
        check(collectionJob?.isActive != true) { "Audio capture is already active." }
        pcm.reset()
        collectionJob = scope.launch {
            input.chunks().collect { chunk ->
                synchronized(pcm) { pcm.write(chunk) }
            }
        }
        input.start()
    }

    suspend fun stop(): ByteArray {
        input.stop()
        collectionJob?.cancel()
        collectionJob?.join()
        collectionJob = null
        return synchronized(pcm) {
            WavEncoder.pcm16Mono(pcm.toByteArray(), input.sampleRateHz)
        }
    }
}
