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
    private val thermalStatus: () -> Int = { -1 },
    private val traceDirectory: java.io.File? = null,
    private val buildProvenance: Map<String, String> = emptyMap()
) {
    private var job: Job? = null
    @Volatile private var output: PiperVoiceOutput? = null
    val running: Boolean get() = job?.isCompleted == false

    fun start(selected: TtsEngine?, status: (String) -> Unit, finished: () -> Unit,
              compareOpenings: Boolean = false, profile: TtsBenchmarkProfile = TtsBenchmarkProfile(openingChars = 320)) {
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
                val engines = listOf(TtsEngine.PIPER_NORTHERN)
                val directories = engines.associateWith { models.ensureReady(it, ::report) }
                val provenance = directories.mapValues { (engine, directory) ->
                    buildProvenance + BenchmarkProvenance.collect(engine, directory)
                }
                val profiles = if (compareOpenings) TtsBenchmarkProfile.comparisonProfiles else listOf(profile)
                val cases = profiles.flatMap { setting ->
                    TtsBenchmarkSamples.all.map { (sample, text) -> Case(TtsEngine.PIPER_NORTHERN, setting, sample, text) }
                }
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
                    val traceName = "$suiteId-$done"
                    val traceFile = traceDirectory?.let { dir ->
                        dir.mkdirs()
                        // Keep twenty-four recent runs (WAV plus boundary log), bounded on disk.
                        dir.listFiles()?.filter { it.extension in listOf("wav", "txt") }
                            ?.sortedByDescending { it.lastModified() }?.drop(46)?.forEach { it.delete() }
                        java.io.File(dir, "$traceName.txt").also {
                            it.writeText("engine=${engine.id} reference=speaker-${engine.speaker}\n" +
                                "profile=${setting.id}\nprovenance=${org.json.JSONObject(provenance.getValue(engine))}\n" +
                                "inputDelivery=paced_4_chars_32ms submissions=${case.submissions}\ntext=$text\nRecording retains up to 180 seconds; see startFrame in trace. PCM offsets exclude playback gaps and speed changes.\n")
                        }
                    }
                    val runLog: (String) -> Unit = { event ->
                        log(event)
                        if (event.startsWith("piper_passage_submit") || event.startsWith("piper_text_policy") || event.startsWith("audio_underrun") ||
                            event.startsWith("audio_supply_gap") || event.startsWith("speech_audio_trace") ||
                            event.startsWith("audio_playback_pace") || event.startsWith("audio_startup_buffer")) {
                            traceFile?.let { synchronized(it) { it.appendText("atMs=${System.currentTimeMillis()} $event\n") } }
                        }
                    }
                    val trace = traceDirectory?.let { SpeechAudioTrace(java.io.File(it, "$traceName.wav"),
                        traceName, seconds = 180, log = runLog) }
                    val speaker = PiperVoiceOutput(directories.getValue(engine).path, engine = engine,
                        normalSpeed = true, fixedChunking = true,
                        openingChars = setting.openingChars ?: SpeechChunker.DEFAULT_OPENING_CHARS,
                        numThreads = setting.threads, benchmarkProfile = setting, benchmarkRun = true,
                        audioTrace = trace,
                        onReady = { ready.complete(Unit) },
                        onMetrics = { metrics ->
                            results.add(engine, "piper-profiles-v1", sample, metrics,
                                TtsBenchmarkRun(suiteId, setting, pass + 1, text, startThermal, thermalStatus(), traceFile?.let { "$traceName.wav" },
                                    inputDelivery = "4 characters every 32 ms after model ready",
                                    submissions = case.submissions, provenance = provenance.getValue(engine)))
                        }, log = runLog)
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
            } catch (error: LinkageError) {
                message = "Benchmark failed: voice runtime could not load. Install an updated APK."
                log("$message ${error.message}")
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

    /** The caller holds the model gate and owns the lifetime of the concurrent listener. */
    internal suspend fun fixedRun(directory: java.io.File, text: String, session: VoiceModelSession,
                         replay: SpeechAudio? = null, pcm: (ShortArray, Int) -> Unit,
                         metrics: (TtsSessionMetrics) -> Unit, events: (String) -> Unit,
                         alongside: suspend CoroutineScope.(PiperVoiceOutput) -> Job?) = coroutineScope {
        val speaker = PiperVoiceOutput(directory.path, engine = TtsEngine.PIPER_NORTHERN,
            numThreads = 4, benchmarkProfile = VoiceTestPacks.reference, benchmarkRun = true,
            modelSession = session, diagnosticReplay = replay, diagnosticPcm = pcm,
            onMetrics = metrics, log = events)
        val listener = alongside(speaker)
        try { speaker.speak(flow { emit(text) }) {} }
        finally { withContext(NonCancellable) { listener?.cancelAndJoin() }; speaker.stopSpeaking() }
    }

    fun stop() { output?.stopSpeaking(); job?.cancel() }

    private data class Case(val engine: TtsEngine, val profile: TtsBenchmarkProfile,
                            val sample: String, val text: String, val submissions: List<String>? = null)
}
