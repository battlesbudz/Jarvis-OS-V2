package com.battlesbudz.jarvis.v2.diagnostics

/** One owner per request; no token callback writes, text retention, or global current-turn slot. */
class PipelineBenchmarkCapture(
    val turnId: String,
    private val channel: String,
    private val capturedAtEpochMs: Long,
    private var provenance: PipelineBenchmarkProvenance,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val started = nowMs()
    private val stages = linkedMapOf("turn_started" to 0L)
    private val measurements = linkedMapOf<String, Double?>()
    private val passes = linkedMapOf<String, PipelineBenchmarkSubmission>()
    private var asr: PipelineBenchmarkAsr? = null
    private var tts: PipelineBenchmarkTts? = null
    private var droppedSubmissions = 0
    private var ended = false
    private var childOutcome: PipelineBenchmarkOutcome? = null
    private var childFailure: String? = null

    @Synchronized fun mark(stage: String) = markAt(stage, nowMs())
    @Synchronized fun markAt(stage: String, monotonicMs: Long) {
        if (!ended && monotonicMs >= started) stages.putIfAbsent(stage, monotonicMs - started)
    }
    @Synchronized fun metric(name: String, value: Number?) {
        if (!ended) measurements[name] = value?.toDouble()?.takeIf(Double::isFinite)
    }
    @Synchronized fun asr(value: PipelineBenchmarkAsr) { if (!ended) asr = value }
    @Synchronized fun tts(value: PipelineBenchmarkTts) { if (!ended) tts = value }
    @Synchronized fun configuration(name: String, value: String) {
        if (!ended) provenance = provenance.copy(configuration = provenance.configuration + (name to value))
    }
    @Synchronized fun transcriptionFallback() {
        if (!ended) asr = asr?.copy(transcriptionFallback = true)
    }
    @Synchronized fun noteOutcome(outcome: PipelineBenchmarkOutcome, failureCode: String? = null) {
        if (!ended && outcome !in setOf(PipelineBenchmarkOutcome.COMPLETE, PipelineBenchmarkOutcome.UNKNOWN)) {
            childOutcome = outcome
            childFailure = failureCode
        }
    }
    @Synchronized fun submission(value: PipelineBenchmarkSubmission) {
        if (ended) return
        if (passes.containsKey(value.submissionId)) return // Exactly one terminal observation per native attempt.
        if (passes.size == 64) { droppedSubmissions++; return }
        val nativeStart = value.metadata["started_monotonic_ms"]?.toLongOrNull()
        passes[value.submissionId] = value.copy(startedOffsetMs = nativeStart?.let { (it - started).takeIf { offset -> offset >= 0 } })
    }
    @Synchronized fun finish(outcome: PipelineBenchmarkOutcome, callId: String? = null,
                             failureCode: String? = null): PipelineBenchmarkTurn? {
        if (ended) return null
        mark("turn_finished")
        ended = true
        measurements["submissions_omitted"] = droppedSubmissions.toDouble()
        val resolvedOutcome = if (outcome in setOf(PipelineBenchmarkOutcome.COMPLETE, PipelineBenchmarkOutcome.UNKNOWN)) childOutcome ?: outcome else outcome
        return PipelineBenchmarkTurn(turnId, callId, channel, capturedAtEpochMs, provenance, resolvedOutcome,
            clock = "System.nanoTime", stageOffsetsMs = stages.toMap(), observedMetrics = measurements.toMap(),
            asr = asr, tts = tts, submissions = passes.values.toList(), failureCode = failureCode ?: childFailure)
    }
}
