package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow

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

    fun start(selected: TtsEngine?, status: (String) -> Unit, finished: () -> Unit,
              compareOpenings: Boolean = false) {
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
                    if (compareOpenings) {
                        // Reverse the second pass to expose warm-up/thermal order effects.
                        val configurations = listOf(2 to 40, 4 to 40, 2 to 90, 4 to 90)
                        for ((pass, settings) in listOf(configurations, configurations.reversed()).withIndex()) {
                            for ((threads, size) in settings) {
                                ensureActive()
                                report("${engine.label}: $threads threads, $size-character opening, pass ${pass + 1} of 2…")
                                val ready = CompletableDeferred<Unit>()
                                val speaker = SherpaKokoroVoiceOutput(directory.path, engine = engine,
                                    normalSpeed = true, fixedChunking = true, openingChars = size, numThreads = threads,
                                    onReady = { ready.complete(Unit) },
                                    onMetrics = { results.add(engine, "voice-speed-v2", "threads-${threads}-opening-${size}-pass-${pass + 1}", it) },
                                    log = log)
                                output = speaker
                                try {
                                    speaker.speak(flow {
                                        ready.await() // Report load separately from a warm voice response.
                                        for (token in openingSample.chunked(4)) { emit(token); delay(32) }
                                    }) {}
                                } finally { output = null }
                            }
                        }
                        continue
                    }
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

    private companion object {
        const val openingSample = "The garden gate is open, and a cool breeze is moving through the trees. " +
            "Beyond the wall, the path leads toward a quiet pond."
    }
}
