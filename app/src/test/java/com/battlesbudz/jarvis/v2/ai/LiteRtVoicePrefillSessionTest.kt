package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
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
        var failPrefill = false
        private var callback: VoiceNativeCallback? = null
        private fun text(input: List<String>): String {
            // Match the C++ RunPrefillAsync precondition that build 708 violated.
            require(input.isNotEmpty()) { "INVALID_ARGUMENT: Input is empty." }
            return input.joinToString("")
        }
        override fun runPrefill(input: List<String>) {
            if (failPrefill) error("prefill failed")
            prefilled.append(text(input))
        }
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

    @Test fun rawNativeTimingPrecedesFilteredVisibleAnswer() = runBlocking {
        val native = Native().apply {
            stream = { callback ->
                callback.onNext("<|channel>thought")
                callback.onNext(" private reasoning")
                callback.onNext("<channel|>Visible answer")
                callback.onDone()
            }
        }
        val events = mutableListOf<InferenceProgress>()
        val session = LiteRtVoicePrefillSession(native) { events += it }
        session.append("Hello")
        val displayed = StringBuilder()
        session.decode { displayed.append(it) }
        assertTrue(events.first().submittedAtMs != null)
        assertTrue(events.any { it.firstRawTokenAtMs != null })
        assertFalse(displayed.toString().contains("private reasoning"))
        session.close()
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

    @Test fun benchmarkSeparatesHiddenNativeTextFromVisibleAnswerAndCountsEmptyCallbacks() = runBlocking {
        val native = Native().apply { stream = { callback ->
            callback.onNext("")
            callback.onNext("<|channel>thought")
            callback.onNext(" private reasoning")
            callback.onNext("<channel|>Visible answer")
            callback.onDone()
        } }
        val records = mutableListOf<PipelineBenchmarkSubmission>()
        val session = LiteRtVoicePrefillSession(native,
            benchmarkModelId = "gemma", benchmarkPurpose = PipelineBenchmarkPurpose.ANSWER,
            benchmarkSink = { records += it })
        session.append("Context")
        session.append(" request")
        val result = session.decode {}
        session.close()
        val record = records.single()
        assertEquals(PipelineBenchmarkOutcome.COMPLETE, record.outcome)
        assertEquals("Visible answer", result.text)
        assertEquals(4.0, record.measurements["callback_count"]!!, 0.0)
        assertEquals(3, record.streamEvents)
        assertEquals(4, record.estimatedOutputTokens)
        assertNull(record.exactOutputTokens)
        assertNotNull(record.firstCallbackMs)
        assertNotNull(record.firstTokenMs)
        assertNotNull(record.measurements["first_visible_text_ms"])
        assertNotNull(record.prefillMs)
        assertEquals(15, record.promptCharacters)
        assertEquals(2.0, record.measurements["prefill_chunks"]!!, 0.0)
    }

    @Test fun benchmarkRecordsFailedAndCancelledNativeSubmissionsWithoutDuplicateCloseReceipts() = runBlocking {
        val errorRecords = mutableListOf<PipelineBenchmarkSubmission>()
        val errorNative = Native().apply { stream = { callback -> callback.onError(IllegalStateException("JNI failure")) } }
        val errorSession = LiteRtVoicePrefillSession(errorNative, benchmarkSink = { errorRecords += it })
        errorSession.append("Hello")
        try { errorSession.decode {}; fail("expected error") } catch (expected: IllegalStateException) {
            assertEquals("JNI failure", expected.message)
        }
        errorSession.close(); errorSession.close()
        assertEquals(PipelineBenchmarkOutcome.ERROR, errorRecords.single().outcome)

        val cancellationRecords = mutableListOf<PipelineBenchmarkSubmission>()
        val started = CompletableDeferred<Unit>()
        val native = Native().apply { stream = { started.complete(Unit) } }
        val session = LiteRtVoicePrefillSession(native, benchmarkSink = { cancellationRecords += it })
        session.append("Hello")
        val job = launch { session.decode {} }
        withTimeout(1000) { started.await() }
        job.cancelAndJoin(); session.close()
        assertTrue(native.cancelled)
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, cancellationRecords.single().outcome)
        assertNull(cancellationRecords.single().firstTokenMs)
    }

    @Test fun abandonedAndFailedPrefillRemainDraftsWithoutGenerationOrTokenClaims() {
        val records = mutableListOf<PipelineBenchmarkSubmission>()
        val session = LiteRtVoicePrefillSession(Native(), benchmarkSink = { records += it })
        session.append("Draft context")
        session.close(); session.close()
        val draft = records.single()
        assertEquals(PipelineBenchmarkPurpose.DRAFT, draft.purpose)
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, draft.outcome)
        assertNull(draft.totalGenerationMs)
        assertNull(draft.estimatedOutputTokens)
        assertEquals("false", draft.metadata["generation_attempted"])
        assertNotNull(draft.prefillMs)

        val failureRecords = mutableListOf<PipelineBenchmarkSubmission>()
        val failed = LiteRtVoicePrefillSession(Native().apply { failPrefill = true }, benchmarkSink = { failureRecords += it })
        try { failed.append("Failed context"); fail("expected failure") } catch (_: IllegalStateException) { }
        failed.close()
        assertEquals(PipelineBenchmarkOutcome.ERROR, failureRecords.single().outcome)
        assertEquals(14, failureRecords.single().promptCharacters)
        assertNull(failureRecords.single().estimatedOutputTokens)
    }
}
