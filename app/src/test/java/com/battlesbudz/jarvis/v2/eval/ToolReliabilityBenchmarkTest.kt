package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ToolCallEngine
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolReliabilityBenchmarkTest {

    private val spec = LocalModelSpec(
        id = "TEST-MODEL",
        fileName = "test-model.litertlm",
        recommendedGpu = false
    )

    /** Owner-path fake mirroring the ModelSetupOperations ownership contract. */
    private class FakeOwner(
        var busy: Boolean = false,
        var integrityOk: Boolean = true,
        var fingerprint: String? = "fingerprint",
        val events: MutableList<String> = mutableListOf()
    ) : ToolReliabilityBenchmark.ModelOwner {
        override fun tryBeginModel(spec: LocalModelSpec): Boolean {
            events += "tryBegin"
            return !busy
        }
        override fun endModelOperation() {
            events += "end"
        }
        override fun closeIdleEngine() {
            events += "closeIdle"
        }
        override fun verifyModelFile(spec: LocalModelSpec): Boolean {
            events += "verify"
            return integrityOk
        }
        override fun modelFingerprint(spec: LocalModelSpec): String? = fingerprint
    }

    /** Engine fake that records conversation history to detect leakage. */
    private class RecordingEngine(
        private val script: Map<String, List<ToolCall>> = emptyMap(),
        private val failAtGenerate: Int = -1,
        private val failOnInitialize: Boolean = false,
        private val generateDelayMs: Long = 0L,
        private val events: MutableList<String> = mutableListOf()
    ) : ToolCallEngine {
        val historyAtGenerate = mutableListOf<List<String>>()
        private val history = mutableListOf<String>()
        private var generateCalls = 0
        var resets = 0
        var closes = 0

        override suspend fun initialize() {
            events += "initialize"
            if (failOnInitialize) throw RuntimeException("init boom")
        }
        override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
            events += "setToolsEnabled"
            return true
        }
        override suspend fun resetConversation() {
            events += "reset"
            resets++
            history.clear()
        }
        override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult {
            events += "generate"
            if (generateDelayMs > 0) delay(generateDelayMs)
            // Trigger on call count, not history size: the runner resets the
            // conversation before every utterance, so history never
            // accumulates and a history-size trigger would never fire.
            if (generateCalls == failAtGenerate) throw RuntimeException("boom")
            generateCalls++
            historyAtGenerate += history.toList()
            history += prompt
            return GenerationResult(
                text = "",
                timeToFirstTokenMs = 0,
                decodeTokensPerSecond = null,
                toolCalls = script[prompt] ?: emptyList()
            )
        }
        override fun close() {
            events += "close"
            closes++
        }
    }

    /** Benchmark with no Android dependencies: engine factory is never called here. */
    private fun benchmark(
        store: ReliabilityReportStore,
        owner: ToolReliabilityBenchmark.ModelOwner = FakeOwner()
    ): ToolReliabilityBenchmark =
        ToolReliabilityBenchmark(
            owner = owner,
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

    @Test fun conversationResetsBetweenFixtures() = runBlocking {
        // Regression: repeated generate calls on one retained conversation
        // contaminate later cases. Every fixture must start with no history.
        val store = InMemoryReliabilityReportStore()
        val engine = RecordingEngine()
        benchmark(store).runSingleModelReport(spec, LiteRtLmToolCallRunner(engine))
        val fixtures = ToolReliabilityFixtures.all()
        assertEquals(fixtures.size, engine.historyAtGenerate.size)
        assertEquals(fixtures.size, engine.resets)
        assertTrue(
            "earlier fixtures leaked into later cases",
            engine.historyAtGenerate.all { it.isEmpty() }
        )
    }

    @Test fun skippedWhenOwnerBusyTouchesNothing() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(busy = true, events = events)
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        val result = benchmark.runModels(listOf(spec))
        assertEquals(listOf(spec.id), result.skipped)
        assertTrue(result.reports.isEmpty())
        assertEquals(0, factoryCalls)
        // Nothing was acquired, so nothing is released.
        assertEquals(listOf("tryBegin"), events)
    }

    @Test fun idleEngineClosedBeforeEngineAllocated() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> events += "factory"; engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        benchmark.runModels(listOf(spec))
        assertTrue(
            "the retained idle engine must close before the benchmark engine is allocated",
            events.indexOf("closeIdle") < events.indexOf("factory")
        )
        assertTrue(events.indexOf("verify") < events.indexOf("factory"))
    }

    @Test fun integrityFailureReleasesOwnerWithoutAllocatingEngine() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(integrityOk = false, events = events)
        var factoryCalls = 0
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { spec -> factoryCalls++; error("no engine") },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected integrity failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("integrity verification"))
        }
        assertEquals(0, factoryCalls)
        assertEquals(listOf("tryBegin", "closeIdle", "verify", "end"), events)
    }

    @Test fun engineInitFailureClosesEngineAndReleasesOwner() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failOnInitialize = true, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected init failure")
        } catch (e: RuntimeException) {
            assertEquals("init boom", e.message)
        }
        assertEquals(1, engine.closes)
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun scoringFailureClosesEngineAndReleasesOwner() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(failAtGenerate = 3, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = store
        )
        try {
            benchmark.runModels(listOf(spec))
            fail("expected scoring failure")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, engine.closes)
        assertNull("no report is persisted for the failed model", store.load(spec.id))
        assertTrue(events.contains("end"))
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun cancellationClosesEngineAndReleasesOwner() = runBlocking {
        val events = mutableListOf<String>()
        val owner = FakeOwner(events = events)
        val engine = RecordingEngine(generateDelayMs = 10_000L, events = events)
        val benchmark = ToolReliabilityBenchmark(
            owner = owner,
            engineFactory = { engine },
            reportStore = InMemoryReliabilityReportStore()
        )
        val job = launch { benchmark.runModels(listOf(spec)) }
        delay(100)
        job.cancelAndJoin()
        assertEquals(1, engine.closes)
        assertTrue(events.contains("end"))
        // Cleanup ordering is preserved: the engine closes before the gate is released.
        assertTrue(events.indexOf("close") < events.indexOf("end"))
    }

    @Test fun scoresBindModelFileIdentityAndSuiteVersion() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val report = ToolReliabilityScorer.scoreModel(
            "M", ToolReliabilityFixtures.all(), FakeToolCallRunner(),
            modelFingerprint = "fp-1"
        )
        store.save(report, ranAtMs = 1L)
        val stored = store.load("M")!!
        assertEquals("fp-1", stored.modelFingerprint)
        assertEquals(ToolReliabilityFixtures.SUITE_VERSION, stored.suiteVersion)
        assertNotNull(store.loadCurrent("M", "fp-1", ToolReliabilityFixtures.SUITE_VERSION))
    }

    @Test fun staleScoresAreNotPresentedAsCurrent() = runBlocking {
        val store = InMemoryReliabilityReportStore()
        val report = ToolReliabilityScorer.scoreModel(
            "M", ToolReliabilityFixtures.all(), FakeToolCallRunner(),
            modelFingerprint = "fp-1"
        )
        store.save(report, ranAtMs = 1L)
        val version = ToolReliabilityFixtures.SUITE_VERSION
        assertNotNull(store.loadCurrent("M", "fp-1", version))
        assertNull("replaced weights must invalidate the score", store.loadCurrent("M", "fp-2", version))
        assertNull("changed fixtures/scorer must invalidate the score", store.loadCurrent("M", "fp-1", version + 1))
        assertNull("unknown model identity must invalidate the score", store.loadCurrent("M", null, version))
        // A score saved without binding (legacy) is never presented as current.
        val legacy = ToolReliabilityScorer.scoreModel("L", ToolReliabilityFixtures.all(), FakeToolCallRunner())
        store.save(legacy, ranAtMs = 1L)
        assertNull(store.loadCurrent("L", "fp-1", version))
    }
}
