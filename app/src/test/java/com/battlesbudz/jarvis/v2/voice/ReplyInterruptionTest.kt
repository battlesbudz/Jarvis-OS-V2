package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReplyInterruptionTest {
    @Test fun correctionCancelsReplyOnceAndRetainsItsAudio() = runBlocking {
        var stopped = 0
        var cancelled = false
        val replyStarted = CompletableDeferred<Unit>()
        val audio = byteArrayOf(1, 2, 3, 4)
        val outcome = withTimeout(2000) {
            runInterruptibleReply(
                reply = { replyStarted.complete(Unit); try { awaitCancellation() } finally { cancelled = true } },
                listen = { confirmed -> replyStarted.await(); confirmed(); CapturedVoiceTurn("Open YouTube", audio) },
                stopReply = { stopped++ })
        }
        assertTrue(cancelled)
        assertEquals(1, stopped)
        assertTrue(outcome is ReplyOutcome.Interrupted)
        assertArrayEquals(audio, (outcome as ReplyOutcome.Interrupted).correction.wav)
    }
    @Test fun normalReplyCancelsAndClosesUnusedListener() = runBlocking {
        val listening = CompletableDeferred<Unit>()
        var listenerClosed = false
        val outcome = runInterruptibleReply(
            reply = { listening.await(); "done" },
            listen = { listening.complete(Unit); try { awaitCancellation() } finally { listenerClosed = true } },
            stopReply = { error("No interruption") }, continuationGraceMs = 1)
        assertEquals(ReplyOutcome.Finished("done"), outcome)
        assertTrue(listenerClosed)
    }
    @Test fun manualStopCancelsBothSidesWithoutReturningCorrection() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        var closed = false
        val job = launch {
            runInterruptibleReply(reply = { awaitCancellation() },
                listen = { ready.complete(Unit); try { awaitCancellation() } finally { closed = true } },
                stopReply = {})
        }
        ready.await()
        job.cancelAndJoin()
        assertTrue(closed)
    }
}
