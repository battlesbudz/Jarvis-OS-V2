package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class CaptureSpeechQueueTest {
    @Test fun cancellationWaitsForNativeWorkAndDoesNotPublishItsLateWords() = runBlocking {
        val input = Input()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray): String {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return "Late words must not become a question"
            }
            override fun finish(): String = error("Cancelled capture must not finalize")
            override fun close() { closed.set(true) }
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(true, .95f)
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { asr },
            onPartialTranscript = { text -> partials.add(text) })
        try {
            capture.start(); input.send(100, 1)
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val stop = async(start = CoroutineStart.UNDISPATCHED) { capture.stop() }
            assertFalse(stop.isCompleted)
            assertFalse(closed.get())
            release.countDown()
            withTimeout(3000) { stop.await() }
            assertTrue(closed.get())
            assertTrue(partials.isEmpty())
            assertEquals("", capture.finalTranscript)
        } finally { release.countDown(); capture.stop() }
    }

    @Test fun slowRecognitionCannotBlockVadOrLoseResumedSpeech() = runBlocking {
        val clock = AtomicLong(0)
        val input = Input()
        val enteredDecode = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val detected = CountDownLatch(4)
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val policies = Collections.synchronizedList(mutableListOf<Boolean>())
        val events = Collections.synchronizedList(mutableListOf<String>())
        var finishes = 0
        val asr = object : StreamingTranscriber {
            override fun accept(pcm: ByteArray) = accept(pcm, true)
            override fun accept(pcm: ByteArray, allowPartial: Boolean): String {
                received.add(pcm[0].toInt()); policies.add(allowPartial)
                if (received.size == 1) {
                    enteredDecode.countDown()
                    check(releaseDecode.await(5, TimeUnit.SECONDS))
                }
                return "What is two plus two?"
            }
            override fun finish(): String { finishes++; return "What is two plus two, plus three?" }
            override fun close() {}
        }
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray): SpeechDecision {
                detected.countDown()
                return SpeechDecision(pcm[0] != 0.toByte(), if (pcm[0] != 0.toByte()) .95f else .01f)
            }
            override fun close() {}
        }
        val capture = AudioTurnCapture(input, CoroutineScope(Dispatchers.Default),
            createDetector = { detector }, createTranscriber = { asr }, nowMs = clock::get,
            trailingSilenceMs = 1200, log = events::add)
        try {
            capture.start()
            clock.set(100); input.send(100, 1)
            assertTrue(withContext(Dispatchers.IO) { enteredDecode.await(3, TimeUnit.SECONDS) })
            clock.set(2800)
            input.send(1400, 0); input.send(1500, 2); input.send(2800, 0)
            assertTrue("VAD must continue during a blocked native decode",
                withContext(Dispatchers.IO) { detected.await(3, TimeUnit.SECONDS) })
            assertEquals(0, finishes)
            releaseDecode.countDown()
            assertTrue(withTimeout(3000) { capture.awaitTurnCompletion() })
            assertEquals(listOf(1, 0, 2, 0), received)
            assertTrue(policies.any { !it })
            assertEquals("What is two plus two, plus three?", capture.finalTranscript)
            assertEquals(1500L, capture.lastSpeechAtMs)
            assertEquals(1, finishes)
            assertTrue(events.any { it.startsWith("capture_silence_detected") })
            assertTrue(events.any { it.startsWith("capture_endpoint_timing") })
        } finally { releaseDecode.countDown(); capture.stop() }
    }

    @Test fun queueOverflowFailsInsteadOfDroppingWords() = runBlocking {
        val input = Input()
        val detector = object : SpeechDetector {
            override fun accept(pcm: ByteArray) = SpeechDecision(true, .95f)
            override fun close() {}
        }
        // Deliberately tiny bound exercises failure without allocating 25 seconds.
        val queue = CaptureSpeechQueue(input, detector, { 0 }, {}, maxBytes = 3200,
            dispatcher = Dispatchers.Unconfined)
        input.send(100, 1); input.send(200, 2)
        input.stop()
        val error = runCatching { queue.frames().collect { /* intentionally not consumed */ } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("incomplete command"))
    }

    private class Input : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        private val channel = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
        override var lastChunkCaptureTimeMs: Long? = null
        override fun chunks() = flow {
            for ((at, pcm) in channel) { lastChunkCaptureTimeMs = at; emit(pcm) }
        }
        suspend fun send(at: Long, marker: Int) { channel.send(at to ByteArray(3200) { marker.toByte() }) }
        override suspend fun start() {}
        override suspend fun stop() { channel.close() }
    }
}
