package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/** Bounded source capture: overflow fails a trial instead of silently truncating its evidence. */
internal class VoiceLoadPcm(private val maxFrames: Int = 24000 * 240) {
    private val chunks = mutableListOf<ShortArray>()
    var frames = 0; private set
    fun append(pcm: ShortArray, rate: Int) {
        require(rate == 24000)
        check(frames + pcm.size <= maxFrames) { "Source exceeded the four-minute diagnostic limit." }
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
