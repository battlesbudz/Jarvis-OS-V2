package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ToolCallEngine

/**
 * On-device driver for the tool-call reliability suite (M8 early enabler).
 *
 * [runModels] loads each model in turn — exactly one resident at a time —
 * runs every canonical fixture through the production conversation path, and
 * persists one [ModelReport] per model. Each model attempt goes through the
 * [ModelOwner] path, which mirrors ModelSetupOperations' model-test
 * ownership: exclusive admission, the retained idle chat engine closed before
 * the benchmark allocates its own native engine (two native engines never
 * coexist), model-file integrity verified before the engine loads, and the
 * gate released afterwards. Each engine is initialized, tool-enabled,
 * exercised, and closed before the next model loads. A model that cannot
 * claim the gate is reported as skipped, not failed. Cancellation propagates;
 * the engine is always closed and ownership always released.
 *
 * Scores measure exact agreement with the fixture set and the tool schema —
 * not whether a requested phone action would succeed. Each saved report is
 * bound to the measured model file's identity and the suite version so a
 * replaced or deleted weight file, or a changed fixture set, never leaves an
 * old score presented as current.
 *
 * The [engineFactory] builds an uninitialized [ToolCallEngine] for a spec
 * (model file, cache dir, GPU choice, and the catalog's OpenApiTool
 * declarations are the caller's responsibility). [runSingleModelReport] is the
 * JVM-testable core: it needs only a [ToolCallRunner].
 */
class ToolReliabilityBenchmark(
    private val owner: ModelOwner,
    private val engineFactory: suspend (LocalModelSpec) -> ToolCallEngine,
    private val reportStore: ReliabilityReportStore
) {
    /**
     * Ownership path for one benchmark model attempt. Production implements
     * this against ModelStore and the session (see ModelSetupOperations);
     * JVM tests use a fake.
     */
    interface ModelOwner {
        /** Acquires exclusive model-operation ownership for [spec]; false when another operation owns it. */
        fun tryBeginModel(spec: LocalModelSpec): Boolean
        /** Releases ownership. Always runs after each model attempt. */
        fun endModelOperation()
        /** Closes the retained idle chat engine so the benchmark owns the only native engine. */
        fun closeIdleEngine()
        /** Verifies the installed model file is unchanged and intact before the engine loads it. */
        fun verifyModelFile(spec: LocalModelSpec): Boolean
        /** Identity of the actual installed model file, used to bind saved scores; null when unknown. */
        fun modelFingerprint(spec: LocalModelSpec): String?
    }

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
            if (!owner.tryBeginModel(spec)) {
                skipped += spec.id
                return@forEachIndexed
            }
            try {
                // The idle chat engine is the live owner of the native
                // runtime. Close it before allocating the benchmark engine so
                // a second native engine is never allocated over it.
                owner.closeIdleEngine()
                check(owner.verifyModelFile(spec)) {
                    "The ${spec.id} model file changed or failed integrity verification. Re-import it."
                }
                val engine = engineFactory(spec)
                try {
                    engine.initialize()
                    engine.setToolsEnabled(true)
                    val report = runSingleModelReport(
                        spec,
                        LiteRtLmToolCallRunner(engine),
                        modelFingerprint = owner.modelFingerprint(spec)
                    ) { done, total ->
                        onProgress(Progress(spec.id, index, models.size, done, total))
                    }
                    reports[spec.id] = report
                } finally {
                    runCatching { engine.close() }
                }
            } finally {
                owner.endModelOperation()
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
        modelFingerprint: String? = null,
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
        val report = ToolReliabilityScorer.scoreModel(
            spec.id, fixtures, counting, modelFingerprint = modelFingerprint
        )
        reportStore.save(report)
        return report
    }
}
