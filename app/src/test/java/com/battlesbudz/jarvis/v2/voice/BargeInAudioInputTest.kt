package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class BargeInAudioInputTest {
    @Test fun echoIsDiscardedAndRecognizerReleasesBeforeFinalTurnOwnerStarts() = runBlocking {
        var clock = 0L
        var openModels = 0
        var maxModels = 0
        var confirmed = 0
        var detectorClosed = false
        fun recognizer(probe: Boolean) = object : StreamingTranscriber {
            init { openModels++; maxModels = maxOf(maxModels, openModels) }
            override fun accept(pcm: ByteArray) = if (probe && clock < 300) "The sky is blue" else "open youtube"
            override fun finish() = "open youtube"
            override fun close() { openModels-- }
        }
        val finalAsr = LazyStreamingTranscriber { recognizer(false) }
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                for (at in listOf(0L, 100L, 300L, 500L, 600L)) {
                    clock = at; emit(byteArrayOf((at / 100).toByte(), 0))
                }
            }
        }
        val gated = BargeInAudioInput(input,
            createDetector = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(true, 0.9f)
                override fun close() { detectorClosed = true }
            } }, playing = { true }, createTranscriber = { recognizer(true) },
            spokenText = { "The sky is blue" }, onConfirmed = {
                assertEquals(0, openModels); confirmed++
            }, nowMs = { clock })
        val delivered = mutableListOf<ByteArray>()
        gated.chunks().collect { delivered += it; finalAsr.accept(it) }
        finalAsr.close()
        assertEquals(1, confirmed)
        assertEquals(1, maxModels)
        assertEquals(0, openModels)
        assertTrue(detectorClosed)
        assertEquals(2, delivered.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 3, 0, 5, 0), delivered[0])
        assertArrayEquals(byteArrayOf(6, 0), delivered[1])
    }
}
