package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoicePreparationTest {
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
}
