package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTurnCaptureTest {
    @Test
    fun stopReturnsWavAndDoesNotPersistRawAudio() = runBlocking<Unit> {
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

    @Test
    fun speechFollowedByQuietNegativeNoiseEndsAfterTwelveHundredMs() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(100, 2000)
        fixture.emit(1299, -1)
        assertFalse(completion.isCompleted)
        fixture.emit(1300, -1)
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.events.any { "reason=trailing_silence" in it })
        fixture.capture.stop()
    }

    @Test
    fun resumedSpeechRestartsTheTrailingSilenceWindow() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(100, -2000)
        fixture.emit(1100, -1)
        fixture.emit(1200, 2000)
        fixture.emit(2399, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(2400, -100)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun initialQuietNoiseTimesOutWithoutSpeech() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(5999, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(6000, -100)
        assertFalse(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun followUpCanWaitOverTenSecondsThenEndNormallyAfterSpeech() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion(initialSilenceTimeoutMs = null)
        }
        fixture.emit(11000, -1)
        assertFalse(completion.isCompleted)
        fixture.emit(12000, -2000)
        fixture.emit(13200, -1)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun stoppingCaptureCancelsPendingEndpointWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion(initialSilenceTimeoutMs = null)
        }
        fixture.capture.stop()
        withTimeout(1000) { completion.join() }
        assertTrue(completion.isCancelled)
    }

    private class CaptureFixture(scope: CoroutineScope) {
        private var clock = 0L
        private val chunks = MutableSharedFlow<ByteArray>()
        val events = mutableListOf<String>()
        private val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = chunks
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }
        val capture = AudioTurnCapture(input, scope, nowMs = { clock }, log = events::add)

        suspend fun emit(atMs: Long, sample: Int) {
            clock = atMs
            chunks.emit(byteArrayOf(sample.toByte(), (sample shr 8).toByte()))
            // Let the collector process the delivered chunk before assertions.
            kotlinx.coroutines.yield()
        }
    }
}

