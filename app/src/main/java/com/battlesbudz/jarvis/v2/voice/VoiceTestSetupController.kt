package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ai.ModelStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** P1 only: reserves the normal model gate, but never opens a microphone or runs inference. */
class VoiceTestSetupController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gate: ModelStore,
    private val sessions: VoiceTestSessionStore,
    private val canStart: () -> Boolean
) {
    data class State(val busy: Boolean = false, val ready: Boolean = false,
                     val message: String = "P1 checks setup and profile recovery. Audio test packs arrive in A2.",
                     val report: String = "")
    private val mutableState = MutableStateFlow(State(report = sessions.report()))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var decision: CompletableDeferred<String>? = null

    fun start() {
        if (job?.isCompleted == false) return
        if (!canStart() || !gate.tryBeginModelOperation()) {
            mutableState.value = State(message = "End the call and stop passive listening or other tests first.", report = sessions.report())
            return
        }
        val completion = CompletableDeferred<String>()
        decision = completion
        mutableState.value = State(busy = true, message = "Verifying installed Paul files…", report = sessions.report())
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var id: String? = null
            var outcome = "cancelled"
            var detail = "Setup cancelled; temporary profile discarded."
            try {
                withContext(Dispatchers.IO) {
                    // Snapshot raw values, including absent/legacy profile keys, without normalizing them.
                    val saved = JSONObject()
                    for (name in listOf("tts_comparison", "voice_input")) {
                        val settings = JSONObject()
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                            if (key == "engine" || key.startsWith("call_profile_")) settings.put(key, value)
                        }
                        saved.put(name, settings)
                    }
                    val audio = context.getSystemService(AudioManager::class.java)
                    val stream = AudioManager.STREAM_MUSIC
                    val meta = JSONObject().put("versionName", BuildConfig.VERSION_NAME)
                        .put("versionCode", BuildConfig.VERSION_CODE).put("sourceCommit", BuildConfig.SOURCE_COMMIT)
                        .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        .put("androidSdk", android.os.Build.VERSION.SDK_INT)
                        .put("selectedAsr", AsrEngine.selected(context).id)
                        .put("selectedAsrVersion", AsrEngine.selected(context).modelVersion)
                        .put("volumeStream", "MUSIC; setup only, no active render route")
                        .put("volumeStep", audio.getStreamVolume(stream)).put("volumeMax", audio.getStreamMaxVolume(stream))
                        .put("activeInputRoute", "not_measured; no capture in P1")
                        .put("activeOutputRoute", "not_measured; no playback in P1")
                        .put("availableAudioDevices", audio.getDevices(AudioManager.GET_DEVICES_ALL).joinToString { "${it.id}:${it.type}" })
                        .put("batteryPercent", context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        .put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                        .put("fixtureScripts", JSONObject(VoiceTestPacks.scripts))
                        .put("sampleIds", TtsBenchmarkSamples.all.keys.joinToString())
                        .put("sampleSha256", JSONObject(TtsBenchmarkSamples.all.mapValues { (_, text) ->
                            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                        }))
                    id = sessions.begin(VoiceTestPacks.SETUP, saved, meta)
                    val dir = File(context.filesDir, "voice-models/${TtsEngine.POCKET_PAUL.directory}")
                    val expected = PocketVoiceSpec.files + (PocketVoiceSpec.PAUL_FILE to PocketVoiceSpec.PAUL_BYTES)
                    check(expected.all { (name, bytes) -> File(dir, name).length() == bytes }) {
                        "Paul model files are missing or incomplete. Install Paul using the existing voice settings, then retry. No download was started."
                    }
                    val provenance = BenchmarkProvenance.collect(TtsEngine.POCKET_PAUL, dir) +
                        mapOf("modelLifecycle" to "no_native_model_loaded_in_P1", "audioRun" to "false")
                    check(provenance["sha256.${PocketVoiceSpec.PAUL_FILE}"] == PocketVoiceSpec.PAUL_SHA256) { "Paul reference verification failed." }
                    ensureActive()
                    sessions.ready(requireNotNull(id), provenance)
                }
                mutableState.value = State(true, true, "P1 ready. Temporary B673 profile is locked to this test session. Complete or cancel to discard it; saved calls keep their original settings.", sessions.report())
                outcome = completion.await()
                detail = "Setup $outcome; temporary profile discarded. No audio was tested."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                outcome = "failed"
                detail = error.message ?: "Setup failed."
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        id?.let { sessions.finish(it, outcome, detail) }
                    }
                } catch (error: Exception) {
                    detail = "Could not finalize test report: ${error.message}. Restart to recover the session."
                } finally {
                    gate.endModelOperation()
                    decision = null
                    mutableState.value = State(message = detail, report = sessions.report())
                }
            }
        }
    }
    fun complete() { if (mutableState.value.ready) decision?.complete("completed") }
    fun cancel() { job?.cancel() }
}
