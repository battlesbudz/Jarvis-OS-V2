package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class IncrementalVoiceInputTest {
    private class Native(val failFirst: Boolean = false) : VoicePrefillSession {
        val input = StringBuilder()
        var decodes = 0
        var closes = 0
        override fun append(text: String) {
            if (failFirst) error("prefill failed")
            input.append(text)
        }
        override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
            decodes++; onToken("Answer.")
            return GenerationResult("Answer.", 0, null)
        }
        override fun close() { closes++ }
    }
    private suspend fun Channel<String>.awaitEvent(prefix: String) = withTimeout(3000) {
        while (true) if (receive().startsWith(prefix)) break
    }

    @Test fun appendedSpeechKeepsOneSessionAndDecodesOnlyAtFinal() = runBlocking {
        val sessions = mutableListOf<Native>()
        val events = Channel<String>(Channel.UNLIMITED)
        val track = IncrementalVoiceInput(this, "Context\nUser: ",
            { Native().also(sessions::add) }, log = { events.trySend(it) })
        track.submit("Tell me about")
        events.awaitEvent("input_partial")
        track.submit("Tell me about history")
        events.awaitEvent("input_prefilled")
        assertEquals("Context\nUser: Tell me ", sessions.single().input.toString())
        assertEquals(0, sessions.single().decodes)
        // A second sentence extends the same input, without a cancelled answer draft.
        track.submit("Tell me about history. Specifically Rome.")
        events.awaitEvent("input_prefilled")
        assertEquals(1, sessions.size)
        val final = "Context\nUser: Tell me about history. Specifically Rome.\n\nReference: verified facts"
        track.answer(final) {}
        assertEquals(final, sessions.single().input.toString())
        assertEquals(1, sessions.single().decodes)
        track.close()
        assertEquals(1, sessions.single().closes)
    }

    @Test fun recognitionRevisionRebuildsOnceAtFinalWithoutRestartingWhileListening() = runBlocking {
        val sessions = mutableListOf<Native>()
        val events = Channel<String>(Channel.UNLIMITED)
        val track = IncrementalVoiceInput(this, "User: ", { Native().also(sessions::add) },
            log = { events.trySend(it) })
        track.submit("Tell me a story")
        events.awaitEvent("input_partial")
        track.submit("Tell me a story about Rome")
        events.awaitEvent("input_prefilled")
        track.submit("Tell us the history of Rome")
        events.awaitEvent("input_revision")
        assertEquals(1, sessions.size)
        assertEquals(0, sessions.single().decodes)
        track.answer("User: Tell us the history of Rome.") {}
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.first().closes)
        assertEquals("User: Tell us the history of Rome.", sessions.last().input.toString())
        track.close()
        assertEquals(1, sessions.last().closes)
    }

    @Test fun finalCorrectionAndChangedContextNeverReuseWrongInput() = runBlocking {
        for (final in listOf("Old context\nUser: Explain quantum computing.",
            "New context\nUser: Explain quantum physics.")) {
            val sessions = mutableListOf<Native>()
            val events = Channel<String>(Channel.UNLIMITED)
            val track = IncrementalVoiceInput(this, "Old context\nUser: ",
                { Native().also(sessions::add) }, log = { events.trySend(it) })
            track.submit("Explain quantum physics in")
            events.awaitEvent("input_partial")
            track.submit("Explain quantum physics in detail")
            events.awaitEvent("input_prefilled")
            track.answer(final) {}
            assertEquals(final, sessions.last().input.toString())
            assertEquals(2, sessions.size)
            track.close()
        }
    }

    @Test fun deferredPrefillStillAnswersFinalTextWithoutAudio() = runBlocking {
        val sessions = mutableListOf<Native>()
        val track = IncrementalVoiceInput(this, "User: ", { Native().also(sessions::add) }, canPrefill = { false })
        track.submit("How does it work?")
        track.seal()
        assertTrue(sessions.isEmpty())
        track.answer("User: How does it work?") {}
        assertEquals("User: How does it work?", sessions.single().input.toString())
        track.close()
    }

    @Test fun failedPrefixPrefillReleasesSessionBeforeFinalRecovery() = runBlocking {
        val sessions = mutableListOf<Native>()
        val events = Channel<String>(Channel.UNLIMITED)
        val track = IncrementalVoiceInput(this, "User: ",
            { Native(failFirst = sessions.isEmpty()).also(sessions::add) }, log = { events.trySend(it) })
        track.submit("How does")
        events.awaitEvent("input_partial")
        track.submit("How does this work?")
        events.awaitEvent("input_prefill_failed")
        track.answer("User: How does this work?") {}
        assertEquals(1, sessions.first().closes)
        assertEquals("User: How does this work?", sessions.last().input.toString())
        track.close()
    }

    @Test fun closeWaitsForNativePrefillAndDoesNotDecode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val events = Channel<String>(Channel.UNLIMITED)
        val track = IncrementalVoiceInput(this, "User: ", {
            object : VoicePrefillSession {
                override fun append(text: String) {
                    entered.complete(Unit)
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    check(!closed)
                }
                override suspend fun decode(onToken: (String) -> Unit): GenerationResult = error("must not decode")
                override fun close() { closed = true }
            }
        }, log = { events.trySend(it) })
        track.submit("How does")
        events.awaitEvent("input_partial")
        track.submit("How does this work?")
        withTimeout(3000) { entered.await() }
        val closing = launch { track.close() }
        yield()
        assertFalse(closed)
        release.countDown()
        closing.join()
        assertTrue(closed)
    }
}
