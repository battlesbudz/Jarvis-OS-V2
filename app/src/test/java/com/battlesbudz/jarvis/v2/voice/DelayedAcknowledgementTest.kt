package com.battlesbudz.jarvis.v2.voice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class DelayedAcknowledgementTest {
    private val audio = SpeechAudio(FillerPhrases.INITIAL, 24000, shortArrayOf(1), 0)
    @Test fun neverSpeaksBeforeConfirmation() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this) { calls++ }; yield()
        assertEquals(0, calls); cue.close()
    }
    @Test fun evenFastAnswerWaitsForInitialUm() = runBlocking {
        val cue = DelayedAcknowledgement(); val spoken = mutableListOf<String>()
        cue.prepare(audio); cue.start(this) { spoken += it.text }; cue.request()
        cue.answerReady(); assertEquals(listOf("Um."), spoken); cue.close()
    }
    @Test fun slowCacheDoesNotExpireAndAnswerCannotSkipInitial() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.start(this) { calls++ }; cue.request()
        val answer = async { cue.answerReady() }; yield(); assertFalse(answer.isCompleted)
        cue.prepare(audio); withTimeout(500) { answer.await() }
        assertEquals(1, calls); cue.close()
    }
    @Test fun repeatsAfterSilenceUntilAnswerThenStops() = runBlocking {
        val cue = DelayedAcknowledgement(); val second = CompletableDeferred<Unit>()
        val spoken = mutableListOf<String>()
        cue.prepare(audio); cue.prepare(audio.copy(text = FillerPhrases.FOLLOWUP))
        cue.start(this, repeatGapMs = 25) {
            spoken += it.text
            if (spoken.size == 2) second.complete(Unit)
        }
        cue.request(); withTimeout(500) { second.await() }; cue.answerReady()
        delay(60); assertEquals(listOf("Um.", "One second."), spoken); cue.close()
    }
    @Test fun fallbackUsesAvailableCachedAudioWhenFollowupMissing() = runBlocking {
        val cue = DelayedAcknowledgement(); val second = CompletableDeferred<Unit>(); var calls = 0
        cue.prepare(audio); cue.start(this, repeatGapMs = 25) {
            assertEquals("Um.", it.text); calls++; if (calls == 2) second.complete(Unit)
        }
        cue.request(); withTimeout(500) { second.await() }; cue.answerReady(); cue.close()
    }
    @Test fun answerWaitsForActiveCueToFinishWithoutOverlap() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        cue.prepare(audio); cue.start(this) { started.complete(Unit); finish.await() }; cue.request(); started.await()
        val answer = async { cue.answerReady() }; yield(); assertFalse(answer.isCompleted)
        finish.complete(Unit); answer.await(); cue.close()
    }
    @Test fun cancellationStopsActiveCue() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var stopped = false
        cue.prepare(audio); cue.start(this) {
            try { started.complete(Unit); awaitCancellation() } finally { stopped = true }
        }
        cue.request(); started.await(); cue.close(); assertTrue(stopped)
    }
    @Test fun voiceSpecificOpeningAlsoWorksForEarlyDirectAnswers() = runBlocking {
        val cue = DelayedAcknowledgement(); val text = "Um, one second."
        val spoken = mutableListOf<String>()
        cue.prepare(audio.copy(text = text)); cue.start(this) { spoken += it.text }
        withTimeout(500) { cue.answerReady(text) }
        assertEquals(listOf(text), spoken); cue.close()
    }
    @Test fun cacheFailureDoesNotDeadlockAnswer() = runBlocking {
        val cue = DelayedAcknowledgement(); cue.preparationFailed(FillerPhrases.INITIAL)
        cue.start(this) { fail("No audio should be played") }; cue.request()
        withTimeout(500) { cue.answerReady() }; cue.close()
    }
}
