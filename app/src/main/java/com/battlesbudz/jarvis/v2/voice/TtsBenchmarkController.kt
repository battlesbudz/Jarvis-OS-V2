package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.util.UUID

/** Runs one native owner at a time and holds the same model-operation gate as live voice calls. */
class TtsBenchmarkController(
    private val scope: CoroutineScope,
    private val gate: ModelStore,
    private val models: TtsModelStore,
    private val results: TtsComparisonStore,
    private val canStart: () -> Boolean,
    private val log: (String) -> Unit,
    private val thermalStatus: () -> Int = { -1 }
) {
    private var job: Job? = null
    @Volatile private var output: SherpaKokoroVoiceOutput? = null
    val running: Boolean get() = job?.isCompleted == false

    fun start(selected: TtsEngine?, status: (String) -> Unit, finished: () -> Unit,
              compareOpenings: Boolean = false, profile: TtsBenchmarkProfile = TtsBenchmarkProfile()) {
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
                val suiteId = UUID.randomUUID().toString()
                val engines = if (compareOpenings || selected == null) TtsBenchmarkProfile.comparisonEngines
                    else listOf(selected)
                val directories = engines.associateWith { models.ensureReady(it, ::report) }
                val profiles = if (compareOpenings) TtsBenchmarkProfile.all + TtsBenchmarkProfile.nativeProfiles else listOf(profile)
                val cases = profiles.flatMap { setting ->
                    TtsBenchmarkSamples.all.flatMap { (sample, text) ->
                        engines.filter { !setting.nativeStreaming || it == TtsEngine.POCKET_PAUL }
                            .map { engine -> Case(engine, setting, sample, text) }
                    }
                }
                check(cases.isNotEmpty()) { "Native audio streaming profiles are available for Paul." }
                // Interleave voices for each identical input; reverse ALL cases on pass two.
                val passes = listOf(cases, cases.reversed())
                var done = 0
                for ((pass, settings) in passes.withIndex()) for (case in settings) {
                    ensureActive()
                    // Thermal status is diagnostic only; never pause or reject a requested test.
                    val startThermal = thermalStatus()
                    val (engine, setting, sample, text) = case
                    report("${++done}/${cases.size * 2}: ${engine.label}, ${setting.label}, $sample, pass ${pass + 1}…")
                    val ready = CompletableDeferred<Unit>()
                    val speaker = SherpaKokoroVoiceOutput(directories.getValue(engine).path, engine = engine,
                        normalSpeed = true, fixedChunking = true,
                        openingChars = setting.openingChars ?: SpeechChunker.DEFAULT_OPENING_CHARS,
                        numThreads = setting.threads, benchmarkProfile = setting,
                        onReady = { ready.complete(Unit) },
                        onMetrics = { metrics ->
                            results.add(engine, "voice-profiles-v4", sample, metrics,
                                TtsBenchmarkRun(suiteId, setting, pass + 1, text, startThermal, thermalStatus()))
                        }, log = log)
                    output = speaker
                    try {
                        speaker.speak(flow {
                            ready.await() // Model load/download are separate from response timing.
                            for (token in text.chunked(4)) { emit(token); delay(32) }
                        }) {}
                    } finally { output = null }
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

    private data class Case(val engine: TtsEngine, val profile: TtsBenchmarkProfile,
                            val sample: String, val text: String)
}
