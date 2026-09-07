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
    fun microphoneStartsBeforeModelsAndBufferedOpeningAudioReachesAsr() = runBlocking<Unit> {
        val chunks = kotlinx.coroutines.channels.Channel<ByteArray>(4)
        var microphoneStarted = false
        val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = kotlinx.coroutines.flow.flow { for (chunk in chunks) emit(chunk) }
            override suspend fun start() {
                microphoneStarted = true
                chunks.send(byteArrayOf(111, 0))
            }
            override suspend fun stop() { chunks.close() }
        }
        val transcriber = FakeTranscriber()
        val capture = AudioTurnCapture(input, this,
            createDetector = { assertTrue(microphoneStarted); FakeDetector() },
            createTranscriber = { assertTrue(microphoneStarted); transcriber })
        capture.start()
        kotlinx.coroutines.yield()
        assertEquals(listOf(111), transcriber.receivedSamples)
        capture.stop()
    }

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
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Unconfined), createDetector = { FakeDetector() })
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
        fixture.emit(100, 2000, speech = true)
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
        fixture.emit(100, -2000, speech = true)
        fixture.emit(1100, -1)
        fixture.emit(1200, 2000, speech = true)
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
        fixture.capture.start(initialSilenceTimeoutMs = null)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.emit(11000, -1)
        assertFalse(completion.isCompleted)
        fixture.emit(12000, -2000, speech = true)
        fixture.emit(13200, -1)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun stoppingCaptureCancelsPendingEndpointWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.capture.awaitTurnCompletion()
        }
        fixture.capture.stop()
        withTimeout(1000) { completion.join() }
        assertTrue(completion.isCancelled)
    }

    @Test
    fun loudNoiseAfterSpeechDoesNotResetTheSilenceTimer() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1100, -4314)
        assertFalse(completion.isCompleted)
        fixture.emit(1300, 4213)
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test
    fun loudNoiseWithoutSpeechIsNotSubmittedAsAUserTurn() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(1000, 4300)
        fixture.emit(6000, -3300)
        assertFalse(withTimeout(1000) { completion.await() })
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test
    fun longIdleCaptureKeepsOnlySixHundredMsOfPreRoll() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        repeat(60) { fixture.emit((it + 1) * 1000L, 3000, samples = 16_000) }
        val wav = fixture.capture.stop()
        assertEquals(44 + 19_200, wav.size)
        assertEquals(1, fixture.detector.releases)
        fixture.capture.stop()
        assertEquals(1, fixture.detector.releases)
    }

    @Test
    fun continuouslyPositiveDetectorCannotRecordPastTwentyFiveSeconds() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        repeat(260) { fixture.emit((it + 1) * 100L, 2000, speech = true, samples = 1600) }
        assertTrue(withTimeout(1000) { completion.await() })
        assertTrue(fixture.events.any { "reason=max_turn_duration" in it })
        assertEquals(44 + 800_000, fixture.capture.stop().size)
    }

    @Test
    fun detectorFailureReachesOwnerAndCanBeCleanedUp() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start()
        fixture.detector.fail = true
        fixture.emit(100, 2000)
        val error = runCatching { fixture.capture.awaitTurnCompletion() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        fixture.capture.stop()
        assertEquals(1, fixture.detector.releases)
    }

    @Test
    fun streamsProvisionalWordsAndSealsTheFlushedFinalTranscript() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        assertEquals(listOf("story about pirates"), fixture.partials)
        assertEquals("", fixture.capture.finalTranscript)
        fixture.emit(1300, -1)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals("story about astronauts", fixture.capture.finalTranscript)
        assertEquals("story about astronauts", fixture.partials.last())
        fixture.capture.stop()
        assertEquals(1, transcriber.finishes)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun explicitStopDoesNotFinalizeOrSubmitAsr() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.stop()
        assertEquals(0, transcriber.finishes)
        assertEquals("", fixture.capture.finalTranscript)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun noiseOnlyIsNotPublishedOrFinalized() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(6000, 4300)
        assertFalse(fixture.capture.awaitTurnCompletion())
        fixture.capture.stop()
        assertTrue(fixture.partials.isEmpty())
        assertEquals(1, transcriber.accepts)
        assertEquals(0, transcriber.finishes)
    }

    @Test
    fun recognizerReceivesOpeningAudioBeforeVadConfirmsSpeech() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 111)
        fixture.emit(900, 222)
        assertTrue(fixture.partials.isEmpty())
        fixture.emit(1000, 333, speech = true)
        assertEquals(listOf(111, 222, 333), transcriber.receivedSamples)
        assertTrue(fixture.partials.isNotEmpty())
        fixture.capture.stop()
    }

    private class FakeTranscriber : StreamingTranscriber {
        var accepts = 0
        val receivedSamples = mutableListOf<Int>()
        var finishes = 0
        var releases = 0
        override fun accept(pcm: ByteArray): String {
            accepts++
            receivedSamples.add((pcm[0].toInt() and 255) or (pcm[1].toInt() shl 8))
            return "story about pirates"
        }
        override fun finish(): String { finishes++; return "story about astronauts" }
        override fun close() { releases++ }
    }

    private class FakeDetector : SpeechDetector {
        var decision = SpeechDecision(false, 0.01f)
        var releases = 0
        var fail = false
        override fun accept(pcm: ByteArray): SpeechDecision {
            check(!fail) { "Test model failure" }
            return decision
        }
        override fun close() { releases++ }
    }

    private class CaptureFixture(scope: CoroutineScope, transcriber: StreamingTranscriber? = null) {
        val partials = mutableListOf<String>()
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
        val detector = FakeDetector()
        val capture = AudioTurnCapture(input, scope, createDetector = { detector }, nowMs = { clock }, log = events::add,
            createTranscriber = transcriber?.let { { it } }, onPartialTranscript = { text, _ -> partials.add(text) })

        suspend fun emit(atMs: Long, sample: Int, speech: Boolean = false, samples: Int = 1) {
            clock = atMs
            detector.decision = SpeechDecision(speech, if (speech) 0.95f else 0.01f)
            chunks.emit(ByteArray(samples * 2) { if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte() })
            // Let the collector process the delivered chunk before assertions.
            kotlinx.coroutines.yield()
        }
    }
}
