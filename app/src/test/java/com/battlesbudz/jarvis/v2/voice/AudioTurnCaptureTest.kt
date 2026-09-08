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
        fixture.emit(6000, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(19999, -100)
        assertFalse(completion.isCompleted)
        fixture.emit(20000, -100)
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
        fixture.emit(20000, -3300)
        assertFalse(withTimeout(1000) { completion.await() })
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test
    fun longIdleCaptureKeepsOnlyTwelveHundredMsOfPreRoll() = runBlocking<Unit> {
        val fixture = CaptureFixture(this)
        fixture.capture.start(initialSilenceTimeoutMs = null)
        repeat(60) { fixture.emit((it + 1) * 1000L, 3000, samples = 16_000) }
        val wav = fixture.capture.stop()
        assertEquals(44 + 38_400, wav.size)
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
        assertEquals(0, transcriber.recoveries)
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
        assertEquals(0, transcriber.recoveries)
        assertEquals("", fixture.capture.finalTranscript)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun noiseOnlyIsNotPublishedOrFinalized() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(20000, 4300)
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

    @Test
    fun reportsAsrMeasurementsWithoutCountingFinalFlushAsPartial() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber())
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true, samples = 1600)
        fixture.emit(1300, 0, samples = 1600)
        assertTrue(fixture.capture.awaitTurnCompletion())
        val result = fixture.metrics.single()
        assertEquals("story about astronauts", result.second)
        assertEquals(200L, result.first.audioMs)
        assertEquals(1, result.first.partialUpdates)
        assertEquals(0L, result.first.firstPartialAfterSpeechMs)
        assertEquals("trailing_silence", result.first.endpointReason)
        fixture.capture.stop()
        assertEquals(1, fixture.metrics.size)
    }

    @Test
    fun cancellationDoesNotPublishACompletedComparison() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber())
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.stop()
        assertTrue(fixture.metrics.isEmpty())
    }

    @Test
    fun emptyPostPlaybackDetectionKeepsMicrophoneOpenAndAcceptsLaterSpeech() = runBlocking<Unit> {
        var created = 0
        val replacement = FakeTranscriber()
        val fixture = CaptureFixture(this, factory = {
            if (created++ == 0) FakeTranscriber("", "") else replacement
        })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        assertEquals(1, fixture.microphoneStarts)
        assertEquals(0, fixture.microphoneStops)
        assertEquals(2, created)
        assertEquals(listOf(2000), replacement.receivedSamples) // The pre-reset tail is replayed.
        fixture.emit(10000, 2000, speech = true)
        fixture.emit(11200, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        assertEquals("story about astronauts", fixture.capture.finalTranscript)
        assertEquals(1, fixture.metrics.single().first.emptyCandidates)
        fixture.capture.stop()
        assertEquals(1, fixture.microphoneStops)
    }

    @Test
    fun emptyStreamRetriesCapturedSpeechBeforeDiscardingIt() = runBlocking<Unit> {
        val transcriber = FakeTranscriber("", "", "can you hear me now")
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 111, samples = 1600)
        fixture.emit(200, 222, speech = true, samples = 1600)
        fixture.emit(1400, 0, samples = 1600)
        assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
        assertEquals("can you hear me now", fixture.capture.finalTranscript)
        assertEquals(listOf("can you hear me now"), fixture.partials)
        assertEquals(1, transcriber.recoveries)
        assertEquals(9600, transcriber.recoveredPcm.size)
        assertEquals(111.toByte(), transcriber.recoveredPcm[0])
        assertEquals(222.toByte(), transcriber.recoveredPcm[3200])
        assertEquals(0, fixture.metrics.single().first.emptyCandidates)
        assertEquals(1, fixture.microphoneStarts)
        assertEquals(listOf(true, false), fixture.recoveryStates)
        fixture.capture.stop()
    }

    @Test
    fun emptyBatchRetryKeepsListeningWithoutSubmittingNoise() = runBlocking<Unit> {
        val first = FakeTranscriber("", "", "")
        var created = 0
        val fixture = CaptureFixture(this, factory = { if (created++ == 0) first else FakeTranscriber() })
        fixture.capture.start()
        fixture.emit(100, 1000, speech = true)
        fixture.emit(1300, 0)
        assertEquals(1, first.recoveries)
        assertEquals(2, created)
        assertFalse(fixture.capture.hasSpeech)
        assertTrue(fixture.partials.isEmpty())
        assertEquals(listOf(true, false), fixture.recoveryStates)
        fixture.emit(2000, 2000, speech = true)
        fixture.emit(3200, 0)
        assertTrue(withTimeout(1000) { fixture.capture.awaitTurnCompletion() })
        fixture.capture.stop()
    }

    @Test
    fun repeatedEmptyDetectionsDoNotRestartTwentySecondInactivityDeadline() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, factory = { FakeTranscriber("", "") })
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        for (start in listOf(100L, 6000L, 12000L)) {
            fixture.emit(start, 2000, speech = true)
            fixture.emit(start + 1200, 0)
            assertFalse(completion.isCompleted)
        }
        fixture.emit(19999, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(20000, 0)
        assertFalse(withTimeout(1000) { completion.await() })
        assertEquals(3, fixture.metrics.single().first.emptyCandidates)
        assertEquals("initial_silence", fixture.metrics.single().first.endpointReason)
        fixture.capture.stop()
    }

    @Test
    fun thinkingPauseDoesNotFinishTurnAndResumedSpeechRestartsThreeSecondWait() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber(), trailingSilenceMs = VoiceCallPolicy.TURN_SILENCE_MS)
        fixture.capture.start()
        val completion = async(start = CoroutineStart.UNDISPATCHED) { fixture.capture.awaitTurnCompletion() }
        fixture.emit(100, 2000, speech = true)
        fixture.emit(1300, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(2600, 2000, speech = true)
        fixture.emit(5599, 0)
        assertFalse(completion.isCompleted)
        fixture.emit(5600, 0)
        assertTrue(withTimeout(1000) { completion.await() })
        fixture.capture.stop()
    }

    @Test
    fun microphoneInterruptionEndsOnlyCaptureWithoutSubmittingPartialSpeech() = runBlocking<Unit> {
        val transcriber = FakeTranscriber()
        val fixture = CaptureFixture(this, transcriber)
        fixture.capture.start()
        fixture.emit(100, 2000, speech = true)
        fixture.capture.yieldMicrophone()
        val error = runCatching { fixture.capture.awaitTurnCompletion() }.exceptionOrNull()
        assertTrue(error is MicrophoneBusyException)
        fixture.capture.stop()
        assertEquals(0, transcriber.finishes)
        assertEquals(0, transcriber.recoveries)
        assertEquals("", fixture.capture.finalTranscript)
        assertTrue(fixture.metrics.isEmpty())
        assertEquals(1, fixture.microphoneStops)
    }

    @Test fun shortUnconfirmedVadWithCredibleTextPreservesOpeningWord() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("open", "open YouTube"))
        fixture.capture.start()
        fixture.emit(100, 1234, speech = false, probability = 0.8f, samples = 1024)
        assertTrue(fixture.capture.hasSpeech)
        fixture.emit(1400, 0)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals("open YouTube", fixture.capture.finalTranscript)
        assertTrue(fixture.events.any { "source=asr_and_vad" in it })
        val wav = fixture.capture.stop()
        assertEquals(1234.toByte(), wav[44])
    }

    @Test fun textWithoutSpeechEvidenceCannotPromoteNoiseToACommand() = runBlocking<Unit> {
        val fixture = CaptureFixture(this, FakeTranscriber("open YouTube", "open YouTube"))
        fixture.capture.start()
        fixture.emit(100, 3000, speech = false, probability = 0.1f)
        assertFalse(fixture.capture.hasSpeech)
        fixture.capture.stop()
    }

    @Test fun fallbackRetainsWordsEarlierThanVadPreRoll() = runBlocking<Unit> {
        val asr = FakeTranscriber("", "", "open YouTube")
        val fixture = CaptureFixture(this, asr)
        fixture.capture.start()
        fixture.emit(100, 111, samples = 1600)
        repeat(15) { fixture.emit(200 + it * 100L, 0, samples = 1600) }
        fixture.emit(1800, 222, speech = true, samples = 1600)
        fixture.emit(3100, 0, samples = 1600)
        assertTrue(fixture.capture.awaitTurnCompletion())
        assertEquals(111.toByte(), asr.recoveredPcm[0])
        assertEquals("open YouTube", fixture.capture.finalTranscript)
        fixture.capture.stop()
    }

    private class FakeTranscriber(
        private val partial: String = "story about pirates",
        private val final: String = "story about astronauts",
        private val recovered: String = ""
    ) : StreamingTranscriber {
        var accepts = 0
        val receivedSamples = mutableListOf<Int>()
        var finishes = 0
        var releases = 0
        var recoveries = 0
        var recoveredPcm = byteArrayOf()
        override fun accept(pcm: ByteArray): String {
            accepts++
            receivedSamples.add((pcm[0].toInt() and 255) or (pcm[1].toInt() shl 8))
            return partial
        }
        override fun finish(): String { finishes++; return final }
        override fun recover(pcm: ByteArray): String {
            assertEquals(1, finishes)
            recoveries++
            recoveredPcm = pcm.copyOf()
            return recovered
        }
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

    private class CaptureFixture(scope: CoroutineScope, transcriber: StreamingTranscriber? = null,
        factory: (() -> StreamingTranscriber)? = transcriber?.let { { it } },
        trailingSilenceMs: Long = 1200L
    ) {
        var microphoneStarts = 0
        var microphoneStops = 0
        val partials = mutableListOf<String>()
        val recoveryStates = mutableListOf<Boolean>()
        val metrics = mutableListOf<Pair<AsrCaptureMetrics, String>>()
        private var clock = 0L
        private val chunks = MutableSharedFlow<ByteArray>()
        val events = mutableListOf<String>()
        private val input = object : AudioInput {
            override val sampleRateHz = 16_000
            override val channelCount = 1
            override fun chunks() = chunks
            override suspend fun start() { microphoneStarts++ }
            override suspend fun stop() { microphoneStops++ }
        }
        val detector = FakeDetector()
        val capture = AudioTurnCapture(input, scope, createDetector = { detector }, nowMs = { clock }, log = events::add,
            createTranscriber = factory, onPartialTranscript = { text, _ -> partials.add(text) },
            onMetrics = { stats, text -> metrics.add(stats to text) }, trailingSilenceMs = trailingSilenceMs,
            onRecognitionRecovery = recoveryStates::add)

        suspend fun emit(atMs: Long, sample: Int, speech: Boolean = false, samples: Int = 1, probability: Float = if (speech) 0.95f else 0.01f) {
            clock = atMs
            detector.decision = SpeechDecision(speech, probability)
            chunks.emit(ByteArray(samples * 2) { if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte() })
            // Let the collector process the delivered chunk before assertions.
            kotlinx.coroutines.yield()
        }
    }
}
