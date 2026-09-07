package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTurnCaptureTest {
    @Test
    fun stopReturnsWavAndDoesNotPersistRawAudio() = runBlocking {
        val chunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
        val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = chunks
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Unconfined))
        capture.start()
        chunks.tryEmit(byteArrayOf(1, 2, 3, 4))
        val wav = capture.stop()
        assertEquals(48, wav.size)
        assertTrue(String(wav, 0, 4) == "RIFF")
    }
}

