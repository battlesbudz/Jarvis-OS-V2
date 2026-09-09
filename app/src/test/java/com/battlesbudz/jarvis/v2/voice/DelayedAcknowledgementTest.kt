package com.battlesbudz.jarvis.v2.voice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class DelayedAcknowledgementTest {
    private val audio = SpeechAudio("One moment.", 24000, shortArrayOf(1), 0)
    @Test fun neverSpeaksBeforeTurnConfirmation() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this, 0) { calls++ }
        yield(); assertEquals(0, calls); cue.close()
    }
    @Test fun fastAnswerCancelsPendingCue() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this, 100) { calls++ }; cue.request()
        cue.answerReady(); delay(110); assertEquals(0, calls); cue.close()
    }
    @Test fun readyAnswerAlsoWinsBeforeCueAudioExists() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.start(this, 0) { calls++ }; cue.request(); yield()
        cue.answerReady(); cue.prepare(audio); yield()
        assertEquals(0, calls); cue.close()
    }
    @Test fun confirmedSlowReplyPlaysOnceAndAnswerWaitsForCueEnd() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        cue.prepare(audio); cue.start(this, 0) { calls++; started.complete(Unit); finish.await() }
        cue.request(); started.await(); cue.request()
        val answer = async { cue.answerReady() }; yield(); assertFalse(answer.isCompleted)
        finish.complete(Unit); answer.await(); assertEquals(1, calls); cue.close()
    }
    @Test fun cancellationStopsActiveCue() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var stopped = false
        cue.prepare(audio); cue.start(this, 0) {
            try { started.complete(Unit); awaitCancellation() } finally { stopped = true }
        }
        cue.request(); started.await(); cue.close(); assertTrue(stopped)
    }
}
