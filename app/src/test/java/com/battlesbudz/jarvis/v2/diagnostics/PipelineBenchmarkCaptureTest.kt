package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarkCaptureTest {
    private val provenance = PipelineBenchmarkProvenance("test", 1)
    @Test fun lateCallbacksCannotMutateFinishedTurnOrCreateAnotherReceipt() {
        var now = 100L
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { now }
        capture.markAt("speech_ended", 90) // An interruption preceding this owner has no invented offset.
        now = 120; capture.mark("recognition_finalized")
        capture.metric("rms_dbfs", -42)
        capture.metric("invalid", Double.NaN)
        capture.configuration("capture_profile", "speech_preserving")
        now = 150
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.CANCELLED, "call"))
        capture.mark("first_reply_audio"); capture.metric("rms_dbfs", 0)
        capture.configuration("capture_profile", "late")
        assertNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(50L, result.stageOffsetsMs["turn_finished"])
        assertFalse(result.stageOffsetsMs.containsKey("speech_ended"))
        assertFalse(result.stageOffsetsMs.containsKey("first_reply_audio"))
        assertEquals(-42.0, result.observedMetrics["rms_dbfs"]!!, 0.0)
        assertNull(result.observedMetrics["invalid"])
        assertEquals("speech_preserving", result.provenance.configuration["capture_profile"])
    }
    @Test fun sharedGenerationFailureCannotBecomeACompletedVoiceSample() {
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { 100L }
        capture.noteOutcome(PipelineBenchmarkOutcome.ERROR, "native_failure")
        capture.noteOutcome(PipelineBenchmarkOutcome.COMPLETE)
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(PipelineBenchmarkOutcome.ERROR, result.outcome)
        assertEquals("native_failure", result.failureCode)
    }
    @Test fun explicitOwnerCancellationTakesPrecedenceOverChildFailure() {
        val capture = PipelineBenchmarkCapture("turn", "voice", 1, provenance) { 100L }
        capture.noteOutcome(PipelineBenchmarkOutcome.ERROR)
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, capture.finish(PipelineBenchmarkOutcome.CANCELLED)?.outcome)
    }
    @Test fun submissionIdentifiersAreDeduplicatedAndOffsetsUseSameClock() {
        val capture = PipelineBenchmarkCapture("turn", "text", 1, provenance) { 100L }
        val pass = PipelineBenchmarkSubmission("pass", PipelineBenchmarkPurpose.RETRY,
            PipelineBenchmarkOutcome.ERROR, metadata = mapOf("started_monotonic_ms" to "125"))
        capture.submission(pass); capture.submission(pass)
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.ERROR))
        assertEquals(1, result.submissions.size)
        assertEquals(25L, result.submissions.single().startedOffsetMs)
        assertNull(result.metrics()["endpoint_to_first_answer_playback_ms"])
    }
    @Test fun boundedSubmissionReceiptsRetainOmissionCount() {
        val capture = PipelineBenchmarkCapture("turn", "text", 1, provenance) { 100L }
        repeat(70) { capture.submission(PipelineBenchmarkSubmission("pass$it", PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkOutcome.COMPLETE)) }
        val result = requireNotNull(capture.finish(PipelineBenchmarkOutcome.COMPLETE))
        assertEquals(64, result.submissions.size)
        assertEquals(6.0, result.observedMetrics["submissions_omitted"]!!, 0.0)
    }
}
