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
    @Volatile private var output: SherpaKokoroVoiceOutput? = null
    val running: Boolean get() = job?.isCompleted == false

    fun start(selected: TtsEngine?, status: (String) -> Unit, finished: () -> Unit,
              compareOpenings: Boolean = false, profile: TtsBenchmarkProfile = TtsBenchmarkProfile(),
              paulIsolation: Boolean = false) {
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
                val comparePaul = !paulIsolation && selected == null && profile.nativeStreaming && !compareOpenings
                val engines = if (paulIsolation) listOf(TtsEngine.POCKET_PAUL) else if (comparePaul) listOf(TtsEngine.POCKET_PAUL) else if (compareOpenings || selected == null) TtsBenchmarkProfile.comparisonEngines
                    else listOf(selected)
                val directories = engines.associateWith { models.ensureReady(it, ::report) }
                val provenance = directories.mapValues { (engine, directory) ->
                    buildProvenance + BenchmarkProvenance.collect(engine, directory)
                }
                val profiles = if (compareOpenings) TtsBenchmarkProfile.all + TtsBenchmarkProfile.nativeProfiles else if (comparePaul)
                    listOf(profile.copy(leadingPeriod = false), profile.copy(leadingPeriod = true)) else listOf(profile)
                val cases = if (paulIsolation) PaulIsolationCases.all.map { test ->
                    Case(TtsEngine.POCKET_PAUL, TtsBenchmarkProfile(threads = 2, openingChars = null,
                        nativeStreaming = true, resetDecoder = test.reset, leadingPeriod = false, bufferMs = 200),
                        test.id, test.text, test.submissions)
                } else profiles.flatMap { setting ->
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
                    val traceName = "$suiteId-$done"
                    val traceFile = traceDirectory?.let { dir ->
                        dir.mkdirs()
                        // Keep twenty-four recent runs (WAV plus boundary log), bounded on disk.
                        dir.listFiles()?.filter { it.extension in listOf("wav", "txt") }
                            ?.sortedByDescending { it.lastModified() }?.drop(46)?.forEach { it.delete() }
                        java.io.File(dir, "$traceName.txt").also {
                            it.writeText("engine=${engine.id} reference=${if (engine == TtsEngine.POCKET_PAUL) PocketVoiceSpec.PAUL_SHA256 else "speaker-${engine.speaker}"}\n" +
                                "profile=${setting.id}\nprovenance=${org.json.JSONObject(provenance.getValue(engine))}\n" +
                                "inputDelivery=${if (paulIsolation) "all_text_upfront" else "paced_4_chars_32ms"} submissions=${case.submissions}\ntext=$text\nRecording retains up to 180 seconds; see startFrame in trace. PCM offsets exclude playback gaps and speed changes.\n")
                        }
                    }
                    val runLog: (String) -> Unit = { event ->
                        log(event)
                        if (event.startsWith("pocket_stream_trace") || event.startsWith("pocket_source_pcm") || event.startsWith("audio_underrun") ||
                            event.startsWith("audio_supply_gap") || event.startsWith("speech_audio_trace") ||
                            event.startsWith("audio_playback_pace") || event.startsWith("audio_startup_buffer")) {
                            traceFile?.let { synchronized(it) { it.appendText("atMs=${System.currentTimeMillis()} $event\n") } }
                        }
                    }
                    val trace = traceDirectory?.let { SpeechAudioTrace(java.io.File(it, "$traceName.wav"),
                        traceName, seconds = 180, log = runLog) }
                    val speaker = SherpaKokoroVoiceOutput(directories.getValue(engine).path, engine = engine,
                        normalSpeed = true, fixedChunking = true,
                        openingChars = setting.openingChars ?: SpeechChunker.DEFAULT_OPENING_CHARS,
                        numThreads = setting.threads, benchmarkProfile = setting, benchmarkRun = true,
                        audioTrace = trace, benchmarkSubmissions = case.submissions,
                        onReady = { ready.complete(Unit) },
                        onMetrics = { metrics ->
                            results.add(engine, if (paulIsolation) "paul-isolation-v1" else "voice-profiles-v4", sample, metrics,
                                TtsBenchmarkRun(suiteId, setting, pass + 1, text, startThermal, thermalStatus(), traceFile?.let { "$traceName.wav" },
                                    inputDelivery = if (paulIsolation) "all text upfront after model ready" else "4 characters every 32 ms after model ready",
                                    submissions = case.submissions, provenance = provenance.getValue(engine)))
                        }, log = runLog)
                    output = speaker
                    try {
                        speaker.speak(flow {
                            ready.await() // Model load/download are separate from response timing.
                            if (paulIsolation) emit(text) else for (token in text.chunked(4)) { emit(token); delay(32) }
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

    fun stop() { output?.stopSpeaking(); job?.cancel() }

    private data class Case(val engine: TtsEngine, val profile: TtsBenchmarkProfile,
                            val sample: String, val text: String, val submissions: List<String>? = null)
}
