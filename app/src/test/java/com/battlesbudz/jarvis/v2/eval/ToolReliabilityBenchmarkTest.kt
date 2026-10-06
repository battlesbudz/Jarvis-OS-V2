package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReliabilityBenchmarkTest {

    private val spec = LocalModelSpec(
        id = "TEST-MODEL",
        fileName = "test-model.litertlm",
        recommendedGpu = false
    )

    /** Benchmark with no Android dependencies: engine factory is never called here. */
    private fun benchmark(store: ReliabilityReportStore): ToolReliabilityBenchmark =
        ToolReliabilityBenchmark(
            tryBeginModel = { true },
            endModelOperation = {},
            engineFactory = { error("no engine in JVM tests") },
            reportStore = store
        )

    @Test fun singleModelReportScoresAndPersists() = runBlocking {
        val fixtures = ToolReliabilityFixtures.all()
        val store = InMemoryReliabilityReportStore()
        val script = fixtures.associate { fixture ->
            fixture.utterance to listOf(ToolCall(fixture.expectedTool, "{}"))
        }
        // Note: "{}" args only pass strict decode for no-parameter tools; this
        // test checks orchestration (progress + persistence), not scoring.
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val report = benchmark(store).runSingleModelReport(
            spec,
            FakeToolCallRunner(script)
        ) { done, total -> progressCalls += done to total }
        assertEquals(fixtures.size, report.total)
        assertEquals(fixtures.size, progressCalls.size)
        assertEquals((1..fixtures.size).toList(), progressCalls.map { it.first })
        assertTrue(progressCalls.all { it.second == fixtures.size })
        val stored = store.load(spec.id)!!
        assertEquals(report.passed, stored.passed)
        assertEquals(report.total, stored.total)
    }

    @Test fun singleModelReportCountsRealPasses() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val runner = FakeToolCallRunner(
            mapOf(
                "How much battery does my phone have?" to
                    listOf(ToolCall("read_battery", "{}"))
            )
        )
        val report = benchmark(store).runSingleModelReport(spec, runner) { _, _ -> }
        val batteryCases = report.caseResults.filter { it.expectedTool == "read_battery" }
        assertEquals(2, batteryCases.size)
        assertEquals(1, batteryCases.count { it.passed })
        assertTrue(report.percent < 100.0)
        assertEquals(report.percent.toInt(), store.load(spec.id)!!.percent)
    }
}
