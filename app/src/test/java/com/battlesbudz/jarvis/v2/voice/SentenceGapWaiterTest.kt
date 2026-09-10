package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SentenceGapWaiterTest {
    private fun waiter() = SentenceGapWaiter(delayMs = 2, repeatMs = 8, pollMs = 1)
    @Test fun readyAnswerAndClosedStreamNeverStartCue() = runBlocking {
        val channel = Channel<Int>(1); channel.send(7); channel.close()
        var calls = 0
        assertEquals(7, waiter().receive(channel, { true }) { calls++ }.getOrThrow())
        assertTrue(waiter().receive(channel, { true }) { calls++ }.isClosed)
        assertEquals(0, calls)
    }
    @Test fun midSentenceStarvationNeverStartsCue() = runBlocking {
        val channel = Channel<Int>(); var calls = 0
        val receive = async { waiter().receive(channel, { false }) { calls++ } }
        delay(25); channel.send(9)
        assertEquals(9, receive.await().getOrThrow()); assertEquals(0, calls)
    }
    @Test fun answerArrivalCancelsCueBeforeReturningPcm() = runBlocking {
        val channel = Channel<Int>(); val started = CompletableDeferred<Unit>(); var released = false
        val receive = async { waiter().receive(channel, { true }) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        } }
        withTimeout(1000) { started.await() }; channel.send(3)
        assertEquals(3, receive.await().getOrThrow()); assertTrue(released)
    }
    @Test fun bufferedPreviousSentenceMustDrainFirst() = runBlocking {
        val channel = Channel<Int>(); var drained = false; val started = CompletableDeferred<Unit>()
        val receive = async { waiter().receive(channel, { drained }) { started.complete(Unit) } }
        delay(25); assertFalse(started.isCompleted); drained = true
        withTimeout(1000) { started.await() }; channel.close(); assertTrue(receive.await().isClosed)
    }
    @Test fun repeatedRealGapsCanCueAndStopCleansUp() = runBlocking {
        val channel = Channel<Int>(); var calls = 0; var released = false
        val repeated = CompletableDeferred<Unit>()
        val receive = async { waiter().receive(channel, { true }) {
            calls++
            if (calls == 2) try { repeated.complete(Unit); awaitCancellation() } finally { released = true }
        } }
        withTimeout(1000) { repeated.await() }; receive.cancelAndJoin(); assertTrue(released)
    }
    @Test fun producerFailureCancelsActiveCueAndIsPreserved() = runBlocking {
        val channel = Channel<Int>(); val started = CompletableDeferred<Unit>(); var released = false
        val failure = IllegalStateException("native failure")
        val receive = async { waiter().receive(channel, { true }) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        } }
        withTimeout(1000) { started.await() }; channel.close(failure)
        assertSame(failure, receive.await().exceptionOrNull()); assertTrue(released)
    }
}
