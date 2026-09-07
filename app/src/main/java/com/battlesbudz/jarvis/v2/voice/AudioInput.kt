package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.Flow

/** Audio is deliberately separated from recognition and Jarvis reasoning. */
interface AudioInput {
    val sampleRateHz: Int
    val channelCount: Int

    /** Emits short PCM16 mono chunks while the input is active. */
    fun chunks(): Flow<ByteArray>

    suspend fun start()
    suspend fun stop()
}

data class AudioFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16
)

