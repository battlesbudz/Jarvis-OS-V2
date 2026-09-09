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
    @Test fun slowModelLoadDoesNotExpireCue() = runBlocking {
        val cue = DelayedAcknowledgement(); val heard = CompletableDeferred<String>()
        cue.start(this, 0) { heard.complete(it.text) }; cue.request()
        delay(1600); cue.prepare(audio)
        assertEquals("One moment.", withTimeout(500) { heard.await() }); cue.close()
    }
    @Test fun selectedFillerUsesMatchingAudioAndOnlyOnce() = runBlocking {
        val cue = DelayedAcknowledgement(); val heard = CompletableDeferred<String>()
        cue.prepare(audio); cue.start(this, 0) { heard.complete(it.text) }
        cue.request(FillerPhrases.CHECKING); yield(); assertFalse(heard.isCompleted)
        cue.prepare(audio.copy(text = FillerPhrases.CHECKING))
        assertEquals(FillerPhrases.CHECKING, heard.await()); cue.request("Other"); cue.close()
    }
    @Test fun neutralFillersVaryWithoutLookupClaims() {
        val phrases = (1..3).map { FillerPhrases.nextNeutral() }
        assertEquals(3, phrases.toSet().size)
        assertTrue(phrases.any { it.startsWith("Uh,") })
        assertTrue(phrases.any { it.startsWith("Um,") })
        assertFalse(phrases.any { it.contains("check", true) })
    }
}
