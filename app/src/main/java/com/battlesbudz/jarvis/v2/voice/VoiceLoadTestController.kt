package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ai.ModelStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** P2 isolation. Production preferences are read only; every condition has a durable result. */
class VoiceLoadTestController(
    private val context: Context, private val scope: CoroutineScope, private val gate: ModelStore,
    private val sessions: VoiceTestSessionStore, private val benchmarks: TtsBenchmarkController,
    private val canStart: () -> Boolean
) {
    data class State(val busy: Boolean = false, val waiting: Boolean = false,
                     val message: String = "P2 compares fixed speech under different loads. Keep listening off.",
                     val report: String = "", val canReplay: Boolean = false)
    private val mutable = MutableStateFlow(State(report = sessions.report()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var decision: CompletableDeferred<Pair<String, String>>? = null
    private var latestSource: SpeechAudio? = null
    private val preferences = context.getSharedPreferences("voice_load_tests", Context.MODE_PRIVATE)
    fun next(notes: String) { decision?.complete("next" to notes) }
    fun replay() { decision?.complete("replay" to "") }
    fun cancel() { job?.cancel() }

    fun start(long: Boolean = false) {
        if (job?.isCompleted == false) return
        if (!canStart() || !gate.tryBeginModelOperation()) {
            mutable.value = State(message = "End the call and stop listening or other tests first.", report = sessions.report()); return
        }
        mutable.value = State(busy = true, message = "Checking installed models…")
        job = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            var id: String? = null
            var outcome = "cancelled"
            var detail = "P2 cancelled. Finished conditions remain in the report."
            val evidence = JSONObject().put("conditions", JSONArray()).put("microphoneSavedToDisk", false)
                .put("physicalTiming", "not_measured").put("liveGemma", "not_run; separate normal-call check")
                .put("scope", if (long) "extended_narration" else "screening")
            var mic = byteArrayOf()
            try {
                yield() // Enter cleanup before dispatch: immediate cancellation must release the gate.
                val audio = context.getSystemService(AudioManager::class.java)
                val saved = JSONObject()
                for (name in listOf("tts_comparison", "voice_input")) {
                    val values = JSONObject()
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                        if (k == "engine" || k.startsWith("call_profile_")) values.put(k, v)
                    }
                    saved.put(name, values)
                }
                id = sessions.begin(VoiceTestPacks.LOAD, saved, JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                    .put("sourceCommit", BuildConfig.SOURCE_COMMIT)
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    .put("volumeStep", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("volumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
                val runId = requireNotNull(id)
                val directory = File(context.filesDir, "voice-models/${TtsEngine.POCKET_PAUL.directory}")
                check((PocketVoiceSpec.files + (PocketVoiceSpec.PAUL_FILE to PocketVoiceSpec.PAUL_BYTES)).all {
                    (name, bytes) -> File(directory, name).length() == bytes
                }) { "Install Paul in voice settings before P2. No download was started." }
                val asr = File(context.filesDir, "voice-models/moonshine-small-en-26-08-21")
                check(asr.isDirectory) { "Install Moonshine before P2. No download was started." }
                val provenance = BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, directory)
                check(provenance["sha256.paul.wav"] == PocketVoiceSpec.PAUL_SHA256)
                sessions.ready(runId, provenance)
                evidence.put("inputDelivery", "fixed_text_upfront; Gemma bypassed")
                    .put("nativeLifecycle", "fresh_session_per_condition; file_cache_not_cold_after_hashing")
                    .put("asr", AsrEngine.MOONSHINE.modelVersion)
                fun save() { sessions.checkpoint(runId, evidence) }
                save()
                suspend fun waitForNext(message: String, replayAllowed: Boolean = false): Pair<String, String> {
                    val pending = CompletableDeferred<Pair<String, String>>()
                    decision = pending
                    mutable.value = State(true, true, message, sessions.report(), replayAllowed)
                    return try { pending.await() } finally { decision = null; mutable.value = mutable.value.copy(waiting = false, canReplay = false) }
                }
                waitForNext("First, record the test phrase. Tap Continue, wait for READ NOW, then say: Actually, tell me what two plus two is.")
                val micEvidence = JSONObject().put("stage", "opening_microphone").put("capturedMs", 0)
                    .put("targetMs", 8000).put("startupAudioIncluded", true)
                    .put("script", VoiceTestPacks.scripts.getValue("N-v1"))
                evidence.put("micFixture", micEvidence)
                save()
                var capturedBytes = 0
                val captureEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
                try {
                    mic = coroutineScope {
                        val input = AndroidAudioInput(this, audioManager = audio, echoCancellation = true,
                            noiseSuppression = true, log = { event ->
                                synchronized(captureEvents) { if (captureEvents.size < 30) captureEvents.add(event.take(700)) }
                            })
                        VoiceLoadMicrophone.capture(input, onReady = {
                            mutable.value = State(true, message = "READ NOW: Actually, tell me what two plus two is. Then remain silent. Recording 0/8 seconds.")
                        }, onProgress = { bytes ->
                            val previousSeconds = capturedBytes / 32000
                            capturedBytes = bytes
                            if (bytes / 32000 != previousSeconds) mutable.value = mutable.value.copy(
                                message = "READ NOW: Actually, tell me what two plus two is. Then remain silent. Recording ${bytes / 32000}/8 seconds.")
                        })
                    }
                } finally {
                    micEvidence.put("capturedMs", capturedBytes / 32).put("capturedBytes", capturedBytes)
                        .put("stage", if (mic.isNotEmpty()) "captured" else "capture_incomplete")
                        .put("captureEvents", synchronized(captureEvents) { JSONArray(captureEvents.toList()) })
                    save()
                }
                micEvidence.put("sha256", hash(mic)).put("durationMs", mic.size / 32).put("stage", "recognizing")
                save()
                mutable.value = State(true, message = "Recording finished. Checking what Moonshine heard…")
                val recognized = MoonshineStreamingTranscriber(asr).use { recognizer ->
                    recognizer.observeSpeech(true)
                    recognizer.accept(mic)
                    recognizer.finish()
                }
                micEvidence.put("recognizedText", recognized).put("stage", "recognized").put("spokenContentVerified", "listener_review")
                save()
                check(recognized.isNotBlank()) { "The recorded phrase was not recognized. Cancel and retry in a quiet room." }
                waitForNext("Recorded phrase: $recognized. If this matches what you said, tap Continue. Otherwise Cancel P2 and retry.")
                // This preparation is explicit and excluded from A/B comparison ordering.
                val text = if (long) TtsBenchmarkSamples.longNarration else TtsBenchmarkSamples.all.getValue("paragraph-v1")
                evidence.put("text", text).put("textSha256", hash(text.toByteArray()))
                    .put("sampleId", if (long) "long-narration-v1" else "paragraph-v1")
                suspend fun runCase(label: String, inputText: String = text, source: SpeechAudio? = null): JSONObject {
                    val item = JSONObject().put("id", UUID.randomUUID().toString()).put("condition", label)
                        .put("state", "running").put("startedAtMs", System.currentTimeMillis())
                        .put("textSha256", hash(inputText.toByteArray())).put("profile", VoiceTestPacks.reference.id)
                        .put("confirmationSuppressed", label == "C" || label == "D")
                        .put("input", when(label) { "C", "E" -> "live_microphone"; "D" -> "recorded_fixture_once_at_5_seconds"; else -> "none" })
                        .put("thermalStart", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                    evidence.getJSONArray("conditions").put(item); save()
                    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
                    val pcm = VoiceLoadPcm()
                    val session = VoiceModelSession()
                    var confirmation = false
                    var metrics: TtsSessionMetrics? = null
                    val log: (String) -> Unit = { event ->
                        // Bounded in-memory event evidence; persist outside real-time callbacks.
                        synchronized(events) { if (events.size < 150 && (event.startsWith("barge_") || event.startsWith("keyword_vad_") ||
                            event.startsWith("isolation_") || event.startsWith("Microphone") ||
                            event.startsWith("audio_underrun") || event.startsWith("tts_session_finished") ||
                            event.contains("event=pcm_parity") || event.contains("event=summary"))) events.add(event.take(700)) }
                    }
                    mutable.value = State(true, message = "$label running. " + when(label) {
                        "C", "E" -> "Remain silent until READ NOW."
                        "D" -> "Remain silent; your recorded phrase is replayed internally."
                        else -> "Listen without speaking."
                    })
                    try {
                        if (label == "D" || label == "E") {
                            // Prime outside playback, then reuse one ASR owner for bounded probes.
                            MoonshineStreamingTranscriber(asr, modelSession = session).close()
                        }
                        benchmarks.fixedRun(directory, inputText, session, source,
                            pcm = pcm::append, metrics = { metrics = it }, events = log) { output ->
                            if (label !in setOf("C", "D", "E")) null else launch {
                                if (label == "E") {
                                    val cue = launch { while (!output.isPlayingAudio) delay(20); delay(5000)
                                        mutable.value = mutable.value.copy(message = "READ NOW: Actually, tell me what two plus two is.") }
                                    try { ReplyVoiceCapture(context, log).listen(output, asr,
                                        onConfirmed = { confirmation = true }, modelSession = session) }
                                    finally { cue.cancelAndJoin() }
                                } else {
                                    val input: AudioInput = if (label == "D") VoiceLoadReplay(mic)
                                    else AndroidAudioInput(this, audioManager = audio, echoCancellation = true, noiseSuppression = true, log = log)
                                    val cue = launch { while (!output.isPlayingAudio) delay(20); delay(5000)
                                        if (label == "C") mutable.value = mutable.value.copy(message = "READ NOW: Actually, tell me what two plus two is.") }
                                    try {
                                        if (label == "D") while (!output.isPlayingAudio) delay(20)
                                        input.start()
                                        if (label == "C") {
                                            MicroInterruptionKeywords(context.assets).use { keywords ->
                                                SileroSpeechDetector.create(context.assets).use { vad ->
                                                    var speechFrames = 0; var hits = 0
                                                    try { input.chunks().collect { chunk ->
                                                        if (vad.accept(chunk).isSpeech) speechFrames++
                                                        if (keywords.accept(chunk) != null) hits++
                                                    } } finally { log("keyword_vad_summary speechFrames=$speechFrames hits=$hits confirmationSuppressed=true") }
                                                }
                                            }
                                        } else {
                                            NaturalBargeInAudioInput(input,
                                                createKeyword = { MicroInterruptionKeywords(context.assets) },
                                                createVad = { SileroSpeechDetector.create(context.assets) },
                                                createTranscriber = { MoonshineStreamingTranscriber(asr, modelSession = session, reserveReplyProbes = false) },
                                                playing = { output.isPlayingAudio }, reference = output::recentSpokenText,
                                                hasPlaybackBudget = output::hasInterruptionBudget,
                                                canContinuePlayback = output::canContinueInterruption,
                                                onConfirmed = { _, _ -> confirmation = true; log("isolation_confirmation_suppressed detector_finishes_after_first_confirmation=true") }, log = log
                                            ).chunks().collect {}
                                        }
                                    } finally { withContext(NonCancellable) { cue.cancelAndJoin(); input.stop() } }
                                }
                            }
                        }
                        item.put("state", if (confirmation && label == "E") "interrupted_as_requested" else "completed")
                        if (label != "E") check(metrics?.completed == true) { "Playback did not complete." }
                        if (label == "E") item.put("interruptionObserved", confirmation)
                        if (pcm.frames > 0 && source == null) latestSource = SpeechAudio(inputText, 24000, pcm.snapshot(), 0)
                    } catch (cancelled: CancellationException) { item.put("state", "cancelled"); throw cancelled }
                    catch (error: Throwable) { item.put("state", "failed").put("error", error.message); throw error }
                    finally {
                        withContext(NonCancellable) {
                            session.close()
                            metrics?.let { m -> item.put("metrics", JSONObject()
                                .put("completed", m.completed).put("loadMs", m.loadMs).put("synthesisMs", m.synthesisMs)
                                .put("audioMs", m.audioMs).put("speed", m.playbackSpeed).put("underruns", m.underruns)
                                .put("supplyGapMs", m.supplyGapMs).put("playedFrames", m.playedFrames)
                                .put("speechFrames", m.speechFrames).put("outputRoute", m.outputRoute)
                                .put("firstTextToPlaybackMs", m.firstTextToPlaybackMs).put("error", m.error)) }
                            item.put("finishedAtMs", System.currentTimeMillis()).put("events", synchronized(events) { JSONArray(events.toList()) })
                                .put("eventLimit", 150).put("eventsMayBeTruncated", events.size >= 150)
                                .put("thermalEnd", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                            save()
                        }
                    }
                    return item
                }
                waitForNext("Ready to prepare the comparison audio. Tap Continue and listen to Paul.")
                runCase("preparation")
                val reference = checkNotNull(latestSource)
                val pass = preferences.getInt("passes", 0)
                check(preferences.edit().putInt("passes", pass + 1).commit())
                val order = if (pass % 2 == 0) listOf("A", "B", "C", "D", "E") else listOf("B", "A", "C", "D", "E")
                evidence.put("order", JSONArray(order)).put("referencePcmSha256", hash(shortBytes(reference.pcm))); save()
                if (long) {
                    // Whole versioned cycles only. Actual playback-head progress, not nominal text length,
                    // determines when a two-minute trial is complete. No interruption trial is mixed in.
                    repeat(3) { trial ->
                        waitForNext("Long narration ${trial + 1}/3. Tap Continue and remain silent until it finishes. Whole passage cycles may repeat intentionally.")
                        var playedMs = 0L
                        var cycle = 0
                        do {
                            check(cycle < 6) { "Long narration did not reach two minutes within six complete cycles." }
                            val item = runCase("B")
                            val m = item.getJSONObject("metrics")
                            check(m.getBoolean("completed")) { "Long narration ended before playback completed." }
                            playedMs += (m.getLong("playedFrames") * 1000.0 / 24000 / m.getDouble("speed")).toLong()
                            item.put("longTrial", trial + 1).put("intentionalFixtureCycle", ++cycle)
                                .put("cumulativePlayedMs", playedMs)
                            save()
                        } while (playedMs < 120_000)
                        val (_, notes) = waitForNext("Long narration ${trial + 1}/3 finished. Note any gaps, crackle, garbling, repetition or accent changes, then Continue.")
                        evidence.put("longTrial${trial + 1}Notes", notes); save()
                    }
                }
                for (label in if (long) emptyList() else order) {
                    waitForNext("Next: $label — " + when(label) {
                        "A" -> "play prepared audio, microphone and synthesis off."
                        "B" -> "generate the same passage, microphone off."
                        "C" -> "generate with live keyword and speech detection. Read only at the cue."
                        "D" -> "generate with bounded recognition of your recorded phrase. Playback will continue even if recognized."
                        else -> "ordinary reply playback and interruption capture. Read the correction at the cue; check whether Paul stops."
                    })
                    if (label == "A") latestSource = reference
                    val item = runCase(label, if (label == "E") VoiceInterruptionTest.passage else text,
                        if (label == "A") reference else null)
                    while (true) {
                        val (action, notes) = waitForNext("$label finished. Enter what you heard: gaps, crackle, garbling, repetition, accent changes. You can replay the source before continuing; label those notes separately.", latestSource != null)
                        if (action == "next") { item.put("listenerNotes", notes); save(); break }
                        val source = checkNotNull(latestSource)
                        // Source listening is explicitly 0.9x here; raw 1.0x export belongs to A3.
                        runCase("source-listening-$label", source.text, source)
                    }
                }
                outcome = "completed"
                detail = "P2 finished. Copy the report. These are baseline measurements, not a voice-quality pass."
                evidence.put("extended120SecondGate", if (long) "three_trials_reached_120s; audible_quality_requires_listener_review" else "not_run_in_screening")
                save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                outcome = "failed"
                detail = "P2 failed: ${error.message ?: error.javaClass.simpleName}"
                evidence.put("failure", JSONObject().put("type", error.javaClass.simpleName)
                    .put("message", error.message).put("location", error.stackTrace.firstOrNull()?.toString()))
                id?.let { sessions.checkpoint(it, evidence) }
            }
            finally {
                mic.fill(0); mic = byteArrayOf(); latestSource = null; decision = null
                try { withContext(NonCancellable + Dispatchers.IO) { id?.let { sessions.finish(it, outcome, detail) } } }
                finally { gate.endModelOperation(); mutable.value = State(message = detail, report = sessions.report()) }
            }
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun shortBytes(pcm: ShortArray) = ByteArray(pcm.size * 2).also { out -> pcm.forEachIndexed { i, s -> out[2*i] = s.toByte(); out[2*i+1] = (s.toInt() shr 8).toByte() } }
}
