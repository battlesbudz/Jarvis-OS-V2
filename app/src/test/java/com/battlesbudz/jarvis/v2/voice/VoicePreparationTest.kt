package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoicePreparationTest {
    @Test fun finalPunctuationKeepsMatchingPreparationButNumericCorrectionsDoNot() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("The value is one point five.")
            started.complete(Unit)
            GenerationResult("The value is one point five.", 1, null)
        }, coalesceMs = 0)
        preparation.submit("what does 1.5 mean", byteArrayOf())
        started.await()
        val draft = preparation.seal("What does 1.5 mean?")!!
        assertFalse(draft.matches("what does 15 mean?"))
        assertEquals("The value is one point five.", draft.consume {}.text)
        preparation.close()
    }

    @Test fun openingIsPreparedSilentlyAndAuthorizedOnlyWhenFinalRoutingConsumesDraft() = runBlocking<Unit> {
        val ready = CompletableDeferred<PreparedSpeechOpening>()
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("A short opening. ")
            GenerationResult("A short opening.", 1, null)
        }, coalesceMs = 0, prepareOpening = { text ->
            PreparedSpeechOpening(text).also {
                it.complete(SpeechAudio(text, 24000, shortArrayOf(200, 201), 30)); ready.complete(it)
            }
        })
        preparation.submit("tell me a short story", byteArrayOf())
        val opening = withTimeout(1000) { ready.await() }
        val draft = preparation.seal("tell me a short story")!!
        assertNull(opening.takeFor(opening.text))
        draft.consume {}
        assertNotNull(opening.takeFor(opening.text))
        preparation.close()
    }

    @Test fun pocketPreparationUsesSameWholeSentenceAsLivePlayback() = runBlocking<Unit> {
        val sentence = "Instead, she became the city's quiet guardian, the keeper of its inner landscape."
        val ready = CompletableDeferred<PreparedSpeechOpening>()
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("Instead, she became the city's quiet guardian, ")
            assertFalse(ready.isCompleted)
            emit("the keeper of its inner landscape. Next")
            GenerationResult(sentence, 1, null)
        }, coalesceMs = 0, sentenceOpenings = true, prepareOpening = { text ->
            PreparedSpeechOpening(text).also { ready.complete(it) }
        })
        preparation.submit("tell me a story", byteArrayOf())
        val opening = withTimeout(1000) { ready.await() }
        val live = SpeechChunker(sentenceMode = true).apply { append("$sentence Next") }
        assertEquals(live.take(), opening.text)
        preparation.close()
    }


    @Test fun punctuationUpdateRetainsRunningDraftUntilConfirmation() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); var calls = 0
        val preparation = VoicePreparation(this, generate = { _, _, _ ->
            calls++; started.complete(Unit); awaitCancellation()
        }, coalesceMs = 0)
        preparation.submit("tell me a story", byteArrayOf())
        withTimeout(1000) { started.await() }
        preparation.submit("Tell me a story?", byteArrayOf())
        val draft = preparation.seal("Tell me a story?")
        assertNotNull(draft); assertFalse(draft!!.failed); assertEquals(1, calls)
        preparation.close()
    }
    @Test fun resumedSpeechInvalidatesAudioEvenBeforeAsrChangesItsWords() = runBlocking<Unit> {
        val ready = CompletableDeferred<PreparedSpeechOpening>()
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("A short opening. "); GenerationResult("A short opening.", 1, null)
        }, coalesceMs = 0, prepareOpening = { text -> PreparedSpeechOpening(text).also { ready.complete(it) } })
        preparation.submit("tell me a short story", byteArrayOf())
        val opening = withTimeout(1000) { ready.await() }
        preparation.speechResumed()
        assertTrue(opening.isDiscarded())
        assertNull(preparation.seal("tell me a short story"))
        preparation.close()
    }

    @Test fun shortenedCorrectionInvalidatesOldWorkImmediately() = runBlocking<Unit> {
        val ready = CompletableDeferred<Unit>()
        val preparation = VoicePreparation(this, generate = { _, _, _ ->
            ready.complete(Unit); GenerationResult("Old answer", 1, null)
        }, coalesceMs = 0)
        preparation.submit("tell a story about pirates", byteArrayOf())
        withTimeout(1000) { ready.await() }
        preparation.submit("no", byteArrayOf())
        assertNull(preparation.seal("tell a story about pirates"))
        preparation.close()
    }

    @Test fun proposedToolCallDiscardsPreparedOpeningAndRemainsDataUntilConfirmation() = runBlocking<Unit> {
        val ready = CompletableDeferred<PreparedSpeechOpening>()
        val generated = CompletableDeferred<Unit>()
        val call = com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}")
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("Your battery is full. ")
            generated.complete(Unit)
            GenerationResult("", 1, null, toolCalls = listOf(call))
        }, coalesceMs = 0, prepareOpening = { text -> PreparedSpeechOpening(text).also { ready.complete(it) } })
        preparation.submit("what is my battery", byteArrayOf())
        val opening = withTimeout(1000) { ready.await() }
        generated.await(); yield()
        assertTrue(opening.isDiscarded())
        val draft = preparation.seal("what is my battery")!!
        assertEquals(listOf(call), draft.consume {}.toolCalls)
        preparation.close()
    }

    @Test fun preparesSilentlyThenStreamsOnlyAfterMatchingFinalTranscript() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            emit("Once upon a time, ")
            started.complete(Unit)
            finish.await()
            emit("pirates sailed.")
            GenerationResult("Once upon a time, pirates sailed.", 1, null)
        }, coalesceMs = 0)
        preparation.submit("tell a story about pirates", byteArrayOf(1))
        withTimeout(1000) { started.await() }
        val heard = StringBuilder()
        assertEquals("", heard.toString())
        val draft = preparation.seal("TELL A STORY ABOUT PIRATES")!!
        val response = async { draft.consume { heard.append(it) } }
        yield()
        assertEquals("Once upon a time, ", heard.toString())
        finish.complete(Unit)
        assertEquals("Once upon a time, pirates sailed.", withTimeout(1000) { response.await() }.text)
        assertTrue(runCatching { draft.consume {} }.isFailure)
        preparation.close()
    }

    @Test fun changedFinalNounDiscardsDraftAndWaitsForCancellation() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val preparation = VoicePreparation(this, generate = { _, _, emit ->
            try { emit("Pirates"); started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { yield(); cleaned = true } }
        }, coalesceMs = 0)
        preparation.submit("tell a story about pirates", byteArrayOf())
        withTimeout(1000) { started.await() }
        assertNull(preparation.seal("tell a story about astronauts"))
        assertTrue(cleaned)
        preparation.close()
    }

    @Test fun revisedHypothesisReplacesPreviousDraftWithoutOverlappingGeneration() = runBlocking<Unit> {
        var active = 0
        var maximum = 0
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val preparation = VoicePreparation(this, generate = { text, _, emit ->
            active++; maximum = maxOf(maximum, active)
            try {
                if (text.endsWith("pirates")) { first.complete(Unit); awaitCancellation() }
                second.complete(Unit); emit("Astronauts!")
                GenerationResult("Astronauts!", 1, null)
            } finally { active-- }
        }, coalesceMs = 0)
        preparation.submit("story about pirates", byteArrayOf())
        withTimeout(1000) { first.await() }
        preparation.submit("story about astronauts", byteArrayOf())
        withTimeout(1000) { second.await() }
        val draft = preparation.seal("story about astronauts")!!
        assertEquals("Astronauts!", draft.consume {}.text)
        assertEquals(1, maximum)
        preparation.close()
    }

    @Test fun finalArrivingBeforePreparationDoesNotUseOldOrPendingWork() = runBlocking<Unit> {
        var generated = false
        val preparation = VoicePreparation(this, generate = { _, _, _ ->
            generated = true; GenerationResult("old", 1, null)
        }, coalesceMs = 10_000)
        preparation.submit("open the instagram", byteArrayOf())
        assertNull(preparation.seal("open the youtube"))
        preparation.submit("open the instagram", byteArrayOf())
        preparation.close()
        assertFalse(generated)
    }

    @Test fun unvalidatedDraftCannotBeConsumed() = runBlocking<Unit> {
        val draft = PreparedVoiceDraft(this, "open instagram", {}) { GenerationResult("", 1, null) }
        assertTrue(runCatching { draft.consume {} }.isFailure)
        draft.discard()
    }
    @Test fun schedulerDenialLeavesNoDraftAndNeverStartsGeneration() = runBlocking<Unit> {
        var called = false
        val deferred = CompletableDeferred<Unit>()
        val preparation = VoicePreparation(this, generate = { _, _, _ -> called = true; error("not admitted") },
            coalesceMs = 0, canPrepare = { deferred.complete(Unit); false })
        preparation.submit("tell me a story", byteArrayOf(1, 2))
        withTimeout(1000) { deferred.await() }
        assertNull(preparation.seal("tell me a story"))
        assertFalse(called); preparation.close()
    }

}
