package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf

/** Runs one native owner at a time and holds the same model-operation gate as live voice calls. */
class TtsBenchmarkController(
    private val scope: CoroutineScope,
    private val gate: ModelStore,
    private val models: TtsModelStore,
    private val results: TtsComparisonStore,
    private val canStart: () -> Boolean,
    private val log: (String) -> Unit
) {
    private var job: Job? = null
    @Volatile private var output: SherpaKokoroVoiceOutput? = null
    val running: Boolean get() = job?.isCompleted == false

    fun start(selected: TtsEngine?, status: (String) -> Unit, finished: () -> Unit) {
        if (running || !canStart()) {
            status("End the call and wait for audio to finish before benchmarking.")
            finished()
            return
        }
        job = scope.launch(Dispatchers.Default) {
            var owned = false
            var message = "Benchmark complete. Results are saved below."
            fun report(text: String) { scope.launch(Dispatchers.Main) { status(text) } }
            try {
                check(gate.tryBeginModelOperation()) { "Another model operation is still running." }
                owned = true
                for (engine in selected?.let { listOf(it) } ?: TtsEngine.entries) {
                    ensureActive()
                    val directory = models.ensureReady(engine, ::report)
                    for ((sample, text) in TtsBenchmarkSamples.all) {
                        ensureActive()
                        report("${engine.label}: ${sample.substringBefore('-')} sample…")
                        val speaker = SherpaKokoroVoiceOutput(directory.path, engine = engine,
                            normalSpeed = true, fixedChunking = true,
                            onMetrics = { results.add(engine, "benchmark-v1", sample, it) }, log = log)
                        output = speaker
                        try { speaker.speak(flowOf(text)) {} }
                        finally { output = null }
                    }
                }
            } catch (cancelled: CancellationException) {
                message = "Benchmark stopped. Completed samples remain saved."
                throw cancelled
            } catch (error: Exception) {
                message = "Benchmark failed: ${error.message ?: "unknown error"}"
                log(message)
            } finally {
                output?.stopSpeaking()
                output = null
                if (owned) gate.endModelOperation()
                withContext(NonCancellable + Dispatchers.Main) { status(message); finished() }
            }
        }
    }

    fun stop() { output?.stopSpeaking(); job?.cancel() }
}
