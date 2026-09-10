package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DelayedAcknowledgementTest {
    private val audio = SpeechAudio(FillerPhrases.INITIAL, 24000, shortArrayOf(1), 0)
    @Test fun neverSpeaksBeforeConfirmation() = runBlocking {
        val cue = DelayedAcknowledgement(); var calls = 0
        cue.prepare(audio); cue.start(this, delayMs = 1) { calls++ }; delay(10)
        assertEquals(0, calls); cue.close()
    }
    @Test fun fastAnswerSkipsAllFillers() = runBlocking {
        val cue = DelayedAcknowledgement(); val spoken = mutableListOf<String>()
        cue.prepare(audio); cue.start(this, delayMs = 100) { spoken += it.text }; cue.request()
        cue.answerReady(); assertTrue(spoken.isEmpty()); cue.close()
    }
    @Test fun missingCacheNeverHoldsAnswer() = runBlocking {
        val cue = DelayedAcknowledgement()
        cue.start(this, delayMs = 1) { fail("No audio cached") }; cue.request(); delay(5)
        withTimeout(100) { cue.answerReady() }; cue.close()
    }
    @Test fun readyAnswerCancelsEvenActiveInitialCue() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var released = false
        cue.prepare(audio); cue.start(this, delayMs = 1) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        }
        cue.request(); withTimeout(500) { started.await() }
        withTimeout(100) { cue.answerReady() }
        assertTrue(released); cue.close()
    }
    @Test fun gapCuesVaryAndContinueAcrossMoreThanOneFollowup() = runBlocking {
        val cue = DelayedAcknowledgement(); val spoken = mutableListOf<String>(); val enough = CompletableDeferred<Unit>()
        cue.prepare(audio); FillerPhrases.VARIATIONS.forEach { cue.prepare(audio.copy(text = it)) }
        cue.start(this, delayMs = 1, repeatGapMs = 5) {
            spoken += it.text; if (spoken.size == 5) enough.complete(Unit)
        }
        cue.request(); withTimeout(500) { enough.await() }; cue.answerReady()
        assertEquals(listOf(FillerPhrases.INITIAL, "One second.", "One moment please.", "Just a second.", "One second."), spoken)
        cue.close()
    }
    @Test fun stageCueTracksActualStageAndDoesNotInventWork() = runBlocking {
        val cue = DelayedAcknowledgement(); val spoken = mutableListOf<String>(); val ready = CompletableDeferred<Unit>()
        cue.prepare(audio.copy(text = "Thinking."))
        cue.prepare(audio.copy(text = "Preparing the audio."))
        cue.updateStage(DelayedAcknowledgement.Stage.GENERATING)
        cue.start(this, delayMs = 1, repeatGapMs = 5) {
            spoken += it.text
            if (spoken.size == 1) cue.updateStage(DelayedAcknowledgement.Stage.SYNTHESIZING)
            else ready.complete(Unit)
        }
        cue.request(); withTimeout(500) { ready.await() }; cue.answerReady()
        assertEquals(listOf("Thinking.", "Preparing the audio."), spoken); cue.close()
    }
    @Test fun aNewStageRequestsItsMissingCueBeforeAnotherGenericVariation() = runBlocking {
        val cue = DelayedAcknowledgement(); val spoken = mutableListOf<String>(); val ready = CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        cue.prepare(audio)
        cue.start(this, delayMs = 1, repeatGapMs = 5, requestPreparation = {
            requested += it; cue.prepare(audio.copy(text = it))
        }) {
            spoken += it.text
            if (spoken.size == 1) cue.updateStage(DelayedAcknowledgement.Stage.GENERATING)
            else ready.complete(Unit)
        }
        cue.request(); withTimeout(500) { ready.await() }; cue.answerReady()
        assertEquals(listOf(FillerPhrases.INITIAL, "Thinking."), spoken)
        assertEquals(listOf("Thinking."), requested); cue.close()
    }

    @Test fun stopCancelsActiveCue() = runBlocking {
        val cue = DelayedAcknowledgement(); val started = CompletableDeferred<Unit>(); var released = false
        cue.prepare(audio); cue.start(this, delayMs = 1) {
            try { started.complete(Unit); awaitCancellation() } finally { released = true }
        }
        cue.request(); withTimeout(500) { started.await() }; cue.close(); assertTrue(released)
    }
}
