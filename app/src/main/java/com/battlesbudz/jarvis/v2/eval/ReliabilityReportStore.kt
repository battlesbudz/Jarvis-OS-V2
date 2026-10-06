package com.battlesbudz.jarvis.v2.eval

import kotlin.math.roundToInt

/**
 * Persisted result of one tool-call reliability run against a model.
 * [summary] is the full [ModelReport.renderSummary] text so the catalog can
 * show the per-model and per-tool breakdown without re-running.
 */
data class StoredReliabilityReport(
    val modelId: String,
    val percent: Int,
    val passed: Int,
    val total: Int,
    val summary: String,
    val ranAtMs: Long
)

/**
 * Storage for the last reliability report per model. The catalog reads from
 * here so scores survive restarts without re-running the suite.
 */
interface ReliabilityReportStore {
    fun save(report: ModelReport, ranAtMs: Long = System.currentTimeMillis())
    fun load(modelId: String): StoredReliabilityReport?
    fun clear(modelId: String)
}

/** In-memory implementation for JVM tests and Compose previews. */
class InMemoryReliabilityReportStore : ReliabilityReportStore {
    private val reports = mutableMapOf<String, StoredReliabilityReport>()

    override fun save(report: ModelReport, ranAtMs: Long) {
        reports[report.modelId] = StoredReliabilityReport(
            modelId = report.modelId,
            percent = report.percent.roundToInt(),
            passed = report.passed,
            total = report.total,
            summary = report.renderSummary(),
            ranAtMs = ranAtMs
        )
    }

    override fun load(modelId: String): StoredReliabilityReport? = reports[modelId]

    override fun clear(modelId: String) {
        reports.remove(modelId)
    }
}
