package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedInterruptionRecognizerTest {
    @Test fun oneOwnerPublishesResultOnlyAfterRelease() = runBlocking {
        var clock = 100L
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { clock += 10; return "actually" }
                override fun finish() = "Actually use the other one"
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        assertTrue(worker.submit(7, ByteArray(32000), 100))
        val result = worker.poll()!!
        assertTrue(closed)
        assertEquals(7L, result.revision)
        assertEquals(40L, result.workMs)
        assertEquals("Actually use the other one", result.text)
        worker.close()
        assertFalse(worker.submit(8, ByteArray(2), 100))
    }
    @Test fun overBudgetDecodeClosesOnItsOwnerAndSuppressesResult() = runBlocking {
        var clock = 0L
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { clock += 800; return "open settings" }
                override fun finish(): String = error("Budget must stop before finalization")
                override fun close() { closed = true }
            }
        }, nowMs = { clock }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertTrue(worker.unavailable); assertTrue(closed); assertNull(worker.poll())
        assertFalse(worker.submit(2, ByteArray(2), 0))
        worker.close()
    }
    @Test fun nativeFailureAndCloseFailureDoNotEscapeOptionalWorker() = runBlocking {
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String = error("native failure")
                override fun finish() = ""
                override fun close() { error("release failed") }
            }
        }, dispatcher = Dispatchers.Unconfined)
        worker.submit(1, ByteArray(32000), 0)
        assertTrue(worker.unavailable); assertNull(worker.poll()); worker.close()
    }
    @Test fun busyWorkerRefusesAnotherWindowWithoutBlockingCaller() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var closed = false
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String {
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return "actually stop"
                }
                override fun finish() = "actually stop"
                override fun close() { closed = true }
            }
        })
        try {
            assertTrue(worker.submit(1, ByteArray(8000), 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.submit(2, ByteArray(8000), 0))
        } finally { release.countDown(); worker.close() }
        assertTrue(closed); assertNull(worker.poll())
    }
    @Test fun playbackPressureStopsFurtherInference() = runBlocking {
        var budget = true
        var calls = 0
        val worker = BoundedInterruptionRecognizer(this, {
            object : StreamingTranscriber {
                override fun accept(pcm: ByteArray): String { calls++; budget = false; return "stop" }
                override fun finish(): String = error("Playback needs priority")
                override fun close() {}
            }
        }, dispatcher = Dispatchers.Unconfined, hasBudget = { budget })
        worker.submit(1, ByteArray(32000), 0)
        assertEquals(1, calls); assertTrue(worker.unavailable); assertNull(worker.poll()); worker.close()
    }
}
