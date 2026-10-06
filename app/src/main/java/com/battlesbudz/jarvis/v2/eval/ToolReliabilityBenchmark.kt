package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall

/**
 * On-device driver for the tool-call reliability suite (M8 early enabler).
 *
 * [runModels] loads each model in turn — exactly one resident at a time —
 * runs every canonical fixture through the production conversation path, and
 * persists one [ModelReport] per model. Each engine is initialized,
 * tool-enabled, exercised, and closed before the next model loads. Model work
 * is serialized against other model operations through [tryBeginModel] (a
 * model that cannot claim the gate is reported as skipped, not failed);
 * [endModelOperation] always runs after each model attempt.
 *
 * The [engineFactory] builds an uninitialized [LiteRtLmEngine] for a spec
 * (model file, cache dir, GPU choice, and the catalog's OpenApiTool
 * declarations are the caller's responsibility). [runSingleModelReport] is the
 * JVM-testable core: it needs only a [ToolCallRunner].
 */
class ToolReliabilityBenchmark(
    private val tryBeginModel: (LocalModelSpec) -> Boolean,
    private val endModelOperation: () -> Unit,
    private val engineFactory: suspend (LocalModelSpec) -> LiteRtLmEngine,
    private val reportStore: ReliabilityReportStore
) {
    data class Progress(
        val modelId: String,
        val finishedModels: Int,
        val totalModels: Int,
        val finishedFixtures: Int,
        val totalFixtures: Int
    )

    data class Result(
        val reports: Map<String, ModelReport>,
        val skipped: List<String>
    )

    suspend fun runModels(
        models: List<LocalModelSpec>,
        onProgress: suspend (Progress) -> Unit = {}
    ): Result {
        val reports = mutableMapOf<String, ModelReport>()
        val skipped = mutableListOf<String>()
        models.forEachIndexed { index, spec ->
            if (!tryBeginModel(spec)) {
                skipped += spec.id
                return@forEachIndexed
            }
            try {
                val engine = engineFactory(spec)
                try {
                    engine.initialize()
                    engine.setToolsEnabled(true)
                    val report = runSingleModelReport(spec, LiteRtLmToolCallRunner(engine)) { done, total ->
                        onProgress(Progress(spec.id, index, models.size, done, total))
                    }
                    reports[spec.id] = report
                } finally {
                    runCatching { engine.close() }
                }
            } finally {
                endModelOperation()
            }
        }
        return Result(reports, skipped)
    }

    /**
     * Scores every fixture against [runner] with per-fixture progress, saves
     * the report, and returns it. JVM-testable with [FakeToolCallRunner].
     */
    suspend fun runSingleModelReport(
        spec: LocalModelSpec,
        runner: ToolCallRunner,
        onProgress: suspend (finished: Int, total: Int) -> Unit = { _, _ -> }
    ): ModelReport {
        val fixtures = ToolReliabilityFixtures.all()
        var finished = 0
        val counting = object : ToolCallRunner {
            override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> =
                runner.runUtterance(modelId, utterance).also {
                    finished++
                    onProgress(finished, fixtures.size)
                }
        }
        val report = ToolReliabilityScorer.scoreModel(spec.id, fixtures, counting)
        reportStore.save(report)
        return report
    }
}
