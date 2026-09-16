package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class VoiceLoadFixturesTest {
    @org.junit.Test fun piperSampleRateIsRetainedAndMixedRatesAreRejected() {
        val pcm = VoiceLoadPcm()
        pcm.append(shortArrayOf(1, 2), 22050)
        org.junit.Assert.assertEquals(22050, pcm.sampleRate)
        try { pcm.append(shortArrayOf(3), 24000); org.junit.Assert.fail("Mixed rates must fail") }
        catch (_: IllegalArgumentException) { }
        org.junit.Assert.assertArrayEquals(shortArrayOf(1, 2), pcm.snapshot())
    }

    @Test fun capturedCallbacksAppearExactlyOnceAndDoNotAliasNativeMemory() {
        val buffer = VoiceLoadPcm(6)
        val callback = shortArrayOf(1, 2, 3)
        buffer.append(callback, 24000)
        callback[0] = 99
        buffer.append(shortArrayOf(4, 5), 24000)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), buffer.snapshot())
        assertEquals(5, buffer.frames)
        assertThrows(IllegalStateException::class.java) { buffer.append(shortArrayOf(6, 7), 24000) }
        assertEquals(5, buffer.frames)
    }
    @Test fun rejectsWrongSourceRateAndInvalidMicrophoneFixtures() {
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadPcm().append(shortArrayOf(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(ByteArray(256002)) }
    }
    private class Microphone(private val stream: Flow<ByteArray>) : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        var stopped = false
        override suspend fun start() {}
        override suspend fun stop() { stopped = true }
        override fun chunks() = stream
    }
    @Test fun startupBacklogStopsAtEightSecondsInsteadOfOverflowing() = runBlocking {
        var delivered = 0
        val input = Microphone(flow {
            // Three queued startup chunks plus another eight seconds from the old wall-clock window.
            repeat(83) { index -> delivered++; emit(ByteArray(3200) { index.toByte() }) }
        })
        val pcm = VoiceLoadMicrophone.capture(input, {})
        assertEquals(256000, pcm.size)
        assertEquals(80, delivered)
        assertEquals(0.toByte(), pcm.first())
        assertEquals(79.toByte(), pcm.last())
        assertTrue(input.stopped)
    }
    @Test fun finalChunkIsBoundedAndShortStreamsKeepActionableFailure() = runBlocking {
        val input = Microphone(flow { repeat(52) { emit(ByteArray(5000) { 7 }) } })
        assertEquals(256000, VoiceLoadMicrophone.capture(input, {}).size)
        assertTrue(input.stopped)
        val short = Microphone(flow { emit(ByteArray(3200)) })
        try { VoiceLoadMicrophone.capture(short, {}); fail("Expected short capture failure") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("received 100 ms of 8000 ms")) }
        assertTrue(short.stopped)
    }
    @Test fun cancellationAndStalledInputAlwaysReleaseMicrophone() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val input = Microphone(flow { ready.complete(Unit); awaitCancellation() })
        val capture = launch { VoiceLoadMicrophone.capture(input, {}) }
        ready.await(); capture.cancelAndJoin()
        assertTrue(input.stopped)
        val stalled = Microphone(flow { awaitCancellation() })
        try { VoiceLoadMicrophone.capture(stalled, {}, timeoutMs = 25); fail("Expected capture timeout") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("timed out")) }
        assertTrue(stalled.stopped)
    }

}
