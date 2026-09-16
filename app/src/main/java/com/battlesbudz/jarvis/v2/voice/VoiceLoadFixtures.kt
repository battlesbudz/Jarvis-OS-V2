package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.flow

/** Bounded source capture: overflow fails a trial instead of silently truncating its evidence. */
internal class VoiceLoadPcm(private val maxFrames: Int = 48000 * 240) {
    private val chunks = mutableListOf<ShortArray>()
    var frames = 0; private set
    var sampleRate = 0; private set
    fun append(pcm: ShortArray, rate: Int) {
        require(rate in 8000..48000 && (sampleRate == 0 || sampleRate == rate))
        sampleRate = rate
        check(frames + pcm.size <= minOf(maxFrames, rate * 240)) { "Source exceeded the four-minute diagnostic limit." }
        chunks += pcm.copyOf(); frames += pcm.size
    }
    fun snapshot(): ShortArray = ShortArray(frames).also { out ->
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(out, offset); offset += chunk.size }
    }
}

/** One captured microphone fixture, delivered once at its original 16 kHz cadence after silence. */
internal class VoiceLoadReplay(private val pcm: ByteArray) : AudioInput {
    init { require(pcm.isNotEmpty() && pcm.size <= 8 * 32000 && pcm.size % 2 == 0) }
    override val sampleRateHz = 16000
    override val channelCount = 1
    override suspend fun start() {}
    override suspend fun stop() {}
    override fun chunks() = flow {
        repeat(50) { emit(ByteArray(3200)); delay(100) }
        for (offset in pcm.indices step 3200) {
            val end = minOf(offset + 3200, pcm.size)
            emit(pcm.copyOfRange(offset, end)); delay((end - offset) / 32L)
        }
        while (true) { emit(ByteArray(3200)); delay(100) }
    }
}

/** Count PCM, not wall time: AndroidAudioInput already queues startup audio before start returns. */
internal object VoiceLoadMicrophone {
    const val TARGET_BYTES = 8 * 32000
    suspend fun capture(input: AudioInput, onReady: () -> Unit,
                        onProgress: (Int) -> Unit = {}, timeoutMs: Long = 12000): ByteArray {
        require(input.sampleRateHz == 16000 && input.channelCount == 1)
        val captured = ByteArrayOutputStream(TARGET_BYTES)
        try {
            input.start()
            onReady()
            val complete = withTimeoutOrNull(timeoutMs) {
                input.chunks().firstOrNull { chunk ->
                    check(chunk.size % 2 == 0) { "Microphone returned an incomplete PCM16 sample." }
                    val count = minOf(chunk.size, TARGET_BYTES - captured.size())
                    captured.write(chunk, 0, count)
                    onProgress(captured.size())
                    captured.size() == TARGET_BYTES
                } != null
            }
            check(complete == true) {
                "Microphone ${if (complete == null) "timed out" else "stream ended"}: received ${captured.size() / 32} ms of 8000 ms."
            }
            return captured.toByteArray()
        } finally { withContext(NonCancellable) { input.stop() } }
    }
}
