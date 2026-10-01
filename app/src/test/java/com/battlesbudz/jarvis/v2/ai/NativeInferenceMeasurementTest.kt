package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import org.junit.Assert.*
import org.junit.Test

class NativeInferenceMeasurementTest {
    @Test fun controlCallbackRawTextAndVisibleTextHaveSeparateClocks() {
        var clock = 1_000_000L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 11_000_000L
        measurement.callback(false)
        clock = 21_000_000L
        measurement.callback(true)
        clock = 51_000_000L
        measurement.callback(true)
        clock = 61_000_000L
        measurement.visibleText()
        clock = 111_000_000L
        measurement.callback(true)
        clock = 121_000_000L
        measurement.terminal()
        clock = 141_000_000L
        val snapshot = measurement.snapshot(40)
        assertEquals(10L, snapshot.firstCallbackMs)
        assertEquals(20L, snapshot.firstRawTextMs)
        assertEquals(60L, snapshot.firstVisibleTextMs)
        assertEquals(120L, snapshot.durationMs)
        assertEquals(140L, snapshot.returnDurationMs)
        assertEquals(100L, snapshot.rawDecodeMs)
        assertEquals(80L, snapshot.visibleDecodeMs)
        assertEquals(4, snapshot.callbackCount)
        assertEquals(10, snapshot.estimatedOutputTokens)
        assertEquals(100.0, snapshot.estimatedRawDecodeTokensPerSecond!!, 0.0001)
        assertEquals(125.0, snapshot.estimatedVisibleTokensPerSecond!!, 0.0001)
        assertEquals(30.0, snapshot.callbackIntervalP50Ms!!, 0.0)
        assertEquals(60.0, snapshot.callbackIntervalP95Ms!!, 0.0)
    }

    @Test fun callbackSamplesAreBoundedWithoutPretendingToBeAllTokenIntervals() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        repeat(5000) { clock += 1_000_000; measurement.callback(true) }
        val snapshot = measurement.snapshot(8)
        assertEquals(5000, snapshot.callbackCount)
        assertEquals(4096, snapshot.callbackIntervalsRetained)
        assertEquals(903, snapshot.callbackIntervalsDropped)
        assertEquals(1.0, snapshot.callbackIntervalMaxMs!!, 0.0)
    }

    @Test fun synchronousCompletionBeforeConsumerDoesNotProduceNegativeDurations() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 1_000_000L; measurement.callback(true)
        clock = 2_000_000L; measurement.terminal()
        clock = 5_000_000L; measurement.visibleText()
        clock = 6_000_000L
        val snapshot = measurement.snapshot(4)
        assertEquals(2L, snapshot.durationMs)
        assertEquals(6L, snapshot.returnDurationMs)
        assertEquals(1L, snapshot.rawDecodeMs)
        assertEquals(1L, snapshot.visibleDecodeMs)
        assertEquals(1000.0, snapshot.estimatedRawDecodeTokensPerSecond!!, 0.0)
    }

    @Test fun submissionDurationDoesNotIncludePreparationAndNoTextIsNotAZeroLatencyToken() {
        var clock = 0L
        val measurement = NativeInferenceMeasurement { clock }
        clock = 50_000_000L
        val submittedAt = clock
        clock = 53_000_000L
        measurement.submitted(submittedAt)
        measurement.callback(false)
        clock = 55_000_000L; measurement.terminal()
        val snapshot = measurement.snapshot(0)
        assertEquals(3L, snapshot.nativeSubmitMs)
        assertEquals(53L, snapshot.firstCallbackMs)
        assertNull(snapshot.firstRawTextMs)
        assertNull(snapshot.firstVisibleTextMs)
        assertNull(snapshot.estimatedRawDecodeTokensPerSecond)
        assertEquals(0, snapshot.estimatedOutputTokens)
    }

    @Test fun terminalReceiptIsEmittedOnceAndDiagnosticsCannotBreakGeneration() {
        val submissions = mutableListOf<PipelineBenchmarkSubmission>()
        val benchmark = NativeInferenceBenchmark("gemma", PipelineBenchmarkPurpose.RETRY,
            PipelineBenchmarkWarmState.WARM, "audio_text", audioBytes = 1024,
            sink = { submissions += it; error("observer failure") })
        benchmark.measurement.callback(true)
        benchmark.measurement.visibleText()
        benchmark.measurement.terminal()
        benchmark.finish(PipelineBenchmarkOutcome.ERROR, 8, 40, 1, error = IllegalArgumentException("private error text"))
        benchmark.finish(PipelineBenchmarkOutcome.COMPLETE, 100, 40, 8)
        val record = submissions.single()
        assertEquals(PipelineBenchmarkOutcome.ERROR, record.outcome)
        assertEquals(PipelineBenchmarkPurpose.RETRY, record.purpose)
        assertNull(record.exactOutputTokens)
        assertNull(record.exactInputTokens)
        assertEquals(2, record.estimatedOutputTokens)
        assertEquals(1024.0, record.measurements["audio_bytes"]!!, 0.0)
        assertEquals("IllegalArgumentException", record.metadata["error_type"])
        assertFalse(record.metadata.values.any { it.contains("private error text") })
        assertEquals("unavailable_litert_android_no_token_ids", record.tokenTelemetrySource)
    }
}
