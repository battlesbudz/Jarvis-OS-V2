package com.battlesbudz.jarvis.v2.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiteRtVoicePrefillSessionTest {
    private class Native : VoiceNativeSession {
        val prefilled = StringBuilder()
        val submitted = StringBuilder()
        var calls = 0
        var closes = 0
        var cancelled = false
        var stream: (VoiceNativeCallback) -> Unit = { it.onNext("Certainly, sir."); it.onDone() }
        private var callback: VoiceNativeCallback? = null
        private fun text(input: List<String>): String {
            // Match the C++ RunPrefillAsync precondition that build 708 violated.
            require(input.isNotEmpty()) { "INVALID_ARGUMENT: Input is empty." }
            return input.joinToString("")
        }
        override fun runPrefill(input: List<String>) { prefilled.append(text(input)) }
        override fun generateContentStream(input: List<String>, callback: VoiceNativeCallback) {
            submitted.append(text(input)); calls++
            this.callback = callback
            stream(callback)
        }
        override fun cancelProcess() {
            cancelled = true
            callback?.onError(CancellationException("cancelled"))
        }
        override fun close() { closes++ }
    }

    @Test fun startsStreamingWithTheBoundaryExactlyOnceAndPreservesPrefilledText() = runBlocking {
        val native = Native()
        val session = LiteRtVoicePrefillSession(native)
        val output = StringBuilder()
        session.append("Context\nCurrent user message:\n")
        session.append("What does that mean?")
        val result = session.decode { output.append(it) }
        assertEquals("<|turn>user\nContext\nCurrent user message:\nWhat does that mean?", native.prefilled.toString())
        assertEquals("<turn|>\n<|turn>model\n", native.submitted.toString())
        assertEquals(1, native.calls)
        assertEquals("Certainly, sir.", result.text)
        assertEquals(result.text, output.toString())
        session.close(); session.close()
        assertEquals(1, native.closes)
    }

    @Test fun synchronousNativeRejectionPropagatesWithoutHangingCleanup() = runBlocking {
        val native = Native().apply { stream = { error("native submission failed") } }
        val session = LiteRtVoicePrefillSession(native)
        session.append("Hello")
        try {
            withTimeout(1000) { session.decode {} }
            fail("expected submission failure")
        } catch (expected: IllegalStateException) {
            assertEquals("native submission failed", expected.message)
        } finally { session.close() }
        assertEquals(1, native.closes)
    }

    @Test fun cancellationStopsTheNativeStreamBeforeClose() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val native = Native().apply { stream = { started.complete(Unit) } }
        val session = LiteRtVoicePrefillSession(native)
        session.append("Hello")
        val job = launch { session.decode {} }
        withTimeout(1000) { started.await() }
        job.cancelAndJoin()
        assertTrue(native.cancelled)
        session.close()
        assertEquals(1, native.closes)
    }
}
