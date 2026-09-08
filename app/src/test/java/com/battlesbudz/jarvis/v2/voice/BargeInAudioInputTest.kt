package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class BargeInAudioInputTest {
    @Test fun echoIsRejectedThenUserOnsetIsDeliveredOnce() = runBlocking {
        var clock = 0L
        var closed = 0
        var confirmed = 0
        var discarded = 0
        val pauses = mutableListOf<Boolean>()
        // Echo starts a probe, disappears during silence, then real speech arrives.
        val samples = listOf(0L to 1, 200L to 0, 700L to 0, 1800L to 2, 1900L to 3)
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                for ((at, value) in samples) { clock = at; emit(byteArrayOf(value.toByte(), 0)) }
            }
        }
        val gated = BargeInAudioInput(input,
            createDetector = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(pcm[0] != 0.toByte(), 1f)
                override fun close() { closed++ }
            } }, playing = { clock < 1800 }, pauseProbe = { pauses += it },
            discardQueued = { discarded++ }, onConfirmed = { confirmed++ }, nowMs = { clock })
        val delivered = gated.chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(2, closed)
        assertEquals(2, discarded)
        assertArrayEquals(byteArrayOf(2, 0), delivered[0])
        assertArrayEquals(byteArrayOf(3, 0), delivered[1])
        assertEquals(listOf(true, false, false), pauses)
    }
}
