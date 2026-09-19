package com.battlesbudz.jarvis.v2

import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.voice.AndroidAudioInput
import kotlinx.coroutines.flow.takeWhile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity() {

    private val runtime get() = JarvisRuntime.get(applicationContext)
    internal val mainHandler get() = runtime.mainHandler
    internal val modelStore get() = runtime.modelStore
    internal val shortTermContext get() = runtime.shortTermContext
    internal val referenceGrounding get() = runtime.referenceGrounding
    internal val factualityVerifier get() = runtime.factualityVerifier
    internal val turnOrchestrator get() = runtime.turnOrchestrator
    internal val promptBuilder get() = runtime.promptBuilder
    internal val actionIntentRouter get() = runtime.actionIntentRouter
    internal val sessionPreferences get() = runtime.sessionPreferences
    internal val diagnosticRecorder get() = runtime.diagnosticRecorder
    internal val voiceCallStore get() = runtime.voiceCallStore
    internal val voiceSessionController get() = runtime.voiceSessionController
    internal val ttsComparisonStore get() = runtime.ttsComparisonStore
    internal val ttsModels get() = runtime.ttsModels
    internal val voicePlayback get() = runtime.voicePlayback
    internal val asrComparisonStore get() = runtime.asrComparisonStore
    internal val voiceTurnJob get() = runtime.voiceTurnJob
    internal val activeVoiceCapture get() = runtime.activeVoiceCapture
    internal val returnToWakeCuePending get() = runtime.returnToWakeCuePending
    internal var conversationEngine: LiteRtLmEngine?
        get() = runtime.conversationEngine
        set(value) { runtime.conversationEngine = value }
    internal var conversationJob: Job?
        get() = runtime.conversationJob
        set(value) { runtime.conversationJob = value }
    internal var conversationCharacters: Int
        get() = runtime.conversationCharacters
        set(value) { runtime.conversationCharacters = value }
    internal var nativeConversationHasContext: Boolean
        get() = runtime.nativeConversationHasContext
        set(value) { runtime.nativeConversationHasContext = value }
    internal var voiceSessionArmed: Boolean
        get() = runtime.voiceSessionArmed
        set(value) { runtime.voiceSessionArmed = value }
    internal var audioRecoveryAttempts: Int
        get() = runtime.audioRecoveryAttempts
        set(value) { runtime.audioRecoveryAttempts = value }
    internal var sessionReport: (String) -> Unit
        get() = runtime.sessionReport
        set(value) { runtime.sessionReport = value }
    private var notificationPermissionAsked = false
    private var pendingSpeechAudio: ByteArray? = null
    private val speechAudioExport = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val bytes = pendingSpeechAudio
        pendingSpeechAudio = null
        if (uri != null && bytes != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openOutputStream(uri) ?: error("Could not open destination")
                    stream.use { it.write(bytes) }
                }
                android.widget.Toast.makeText(this@MainActivity, "Reply audio saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "Could not save reply audio: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val voiceCallResumer by lazy {
        com.battlesbudz.jarvis.v2.voice.VoiceCallResumer(voiceSessionController)
    }
    private val activeVoiceOutput get() = runtime.activeVoiceOutput
    private data class PendingVoiceTurn(
        val start: Boolean,
        val report: (String) -> Unit,
        val onTranscript: (String, String, Boolean) -> Unit,
        val onFinished: (String) -> Unit
    )
    private var pendingVoiceTurn: PendingVoiceTurn? = null
    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingVoiceTurn
        pendingVoiceTurn = null
        if (pending == null) return@registerForActivityResult
        if (granted) {
            runVoiceTurn(pending.start, pending.report, pending.onTranscript, pending.onFinished)
        } else {
            voiceSessionArmed = false
            pending.report("Microphone permission is required for Voice Calls.")
            pending.onFinished("Voice Call could not start because microphone permission was denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val interruptedSession = sessionPreferences.getBoolean("sending", false)
        if (!voiceSessionArmed) shortTermContext.restoreSummary(
            if (interruptedSession) null else {
                savedInstanceState?.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY)
                    ?: sessionPreferences.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, null)
            }
        )
        if (interruptedSession) {
            // Do not reuse context captured while the native engine was being
            // torn down. The visible transcript remains recoverable.
            sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
        }
        setContent {
            JarvisApp(
                store = modelStore,
                onSelectModel = ::selectAiModel,
                onDeleteModel = ::deleteAiModel,
                voicePlayback = voicePlayback,
                voiceModelStore = ttsModels,
                initialVoiceCalls = voiceCallStore.list(),
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onVoiceTurn = { start, report, onTranscript, onFinished ->
                    com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
                    com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.report("Preparing microphone…")
                    audioRecoveryAttempts = 0
                    returnToWakeCuePending.set(false)
                    voiceSessionArmed = start
                    sessionReport = report
                    runVoiceTurn(start, report, onTranscript, onFinished)
                },
                onWakeTest = { report, finished -> runWakeTest(report, finished) },
                onStopWakeTest = { wakeTestJob?.cancel() },
                onEndVoiceCall = { report -> endVoiceCall(report) },
                onResumeVoiceCall = { call, onComplete ->
                    lifecycleScope.launch {
                        val result = voiceCallResumer.resume(call) {
                            pendingVoiceTurn = null
                            activeVoiceOutput?.stopSpeaking()
                            val previousVoice = voiceTurnJob
                            val previousConversation = conversationJob
                            previousVoice?.cancel(kotlinx.coroutines.CancellationException("resuming_saved_voice_call"))
                            previousConversation?.cancel()
                            previousVoice?.join()
                            previousConversation?.join()
                        }
                        result.onSuccess {
                            startVoiceDiagnostics("Voice Call ${it.id} (resumed)")
                        }.onFailure {
                            diagnosticRecorder.record("Voice resume failed: ${it.stackTraceToString().take(4000)}")
                        }
                        onComplete(result.exceptionOrNull()?.let {
                            "Could not resume this call: ${it.message ?: "unknown error"}"
                        })
                    }
                },
                onDeleteVoiceCall = { callId -> voiceCallStore.delete(callId) },
                onRefreshVoiceCalls = { voiceCallStore.list() },
                onDownloadGemma = { onProgress, onStatus, onFinished ->
                    downloadGemmaAndTest(onProgress, onStatus, onFinished)
                },
                onImportModel = { uri, spec, report -> importModel(uri, spec, report) },
                onCopyDiagnostics = { transcript -> copyDiagnostics(transcript) },
                onExportSpeechAudio = { exportSpeechAudio() },

            )
        }
    }

    /** Streaming ASR -> speculative Gemma -> final validation -> tools and speech. */
    private fun runVoiceTurn(
        start: Boolean,
        report: (String) -> Unit,
        onTranscript: (String, String, Boolean) -> Unit,
        onFinished: (String) -> Unit
    ) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTurn = PendingVoiceTurn(start, report, onTranscript, onFinished)
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!start || voiceTurnJob?.isActive == true) {
            val message = "Voice Call turn failed: the previous turn is still finishing."
            report(message)
            onFinished(message)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtime.attachUi(report, onTranscript, onFinished)
        runtime.arm()
        try {
            startForegroundService(android.content.Intent(this, com.battlesbudz.jarvis.v2.voice.VoiceCallService::class.java))
        } catch (error: Exception) {
            runtime.endVoiceCall(report)
            onFinished("Voice Call turn failed: background audio could not start: ${error.message}")
        }
    }

    private var wakeTestJob: Job? = null

    private fun runWakeTest(report: (String) -> Unit, finished: () -> Unit) {
        if (wakeTestJob?.isActive == true || voiceSessionArmed || !modelStore.tryBeginModelOperation()) {
            report("Stop the current session or model operation before testing the wake word.")
            finished()
            return
        }
        startVoiceDiagnostics("microWakeWord microphone test")
        wakeTestJob = lifecycleScope.launch(Dispatchers.Default) {
            fun status(message: String) { mainHandler.post { report(message) } }
            val input = AndroidAudioInput(this,
                audioManager = getSystemService(android.media.AudioManager::class.java),
                onWaiting = { status("Wake test paused — another app is using the microphone.") })
            try {
                val directory = com.battlesbudz.jarvis.v2.voice.WakeWordModelStore(applicationContext).ensureReady(::status)
                val detected = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    com.battlesbudz.jarvis.v2.voice.PassiveWakeListener(directory,
                        log = { diagnosticRecorder.record("Wake test: $it") },
                        onReady = { status("Say Hey Jarvis — testing microphone and wake model only.") },
                        onLevel = { rms, score -> status("Microphone level: $rms · Wake score: ${"%.3f".format(java.util.Locale.US, score)} / 0.97") }
                    ).use { wake -> input.start(); wake.awaitWake(input) }
                    true
                } == true
                if (detected) {
                    diagnosticRecorder.recordImportant("Wake test passed: Hey Jarvis matched without ASR or Gemma.")
                    status("Hey Jarvis detected! Wake test passed.")
                    com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                        com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.COMMAND_READY,
                        log = { diagnosticRecorder.recordImportant(it) })
                } else {
                    diagnosticRecorder.recordImportant("Wake test ended: no match in 30 seconds.")
                    status("No wake detected in 30 seconds. Copy diagnostics to share this test.")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                diagnosticRecorder.recordImportant("Wake test stopped.")
                status("Wake test stopped — microphone off.")
                throw cancelled
            } catch (error: Throwable) {
                diagnosticRecorder.recordImportant("Wake test failed: ${error.message}")
                status("Wake test failed: ${error.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { input.stop() }
                modelStore.endModelOperation()
                mainHandler.post { finished() }
            }
        }
    }

    private fun endVoiceCall(report: (String) -> Unit) {
        pendingVoiceTurn = null
        runtime.endVoiceCall(report)
    }

    private fun startVoiceDiagnostics(label: String) = runtime.startVoiceDiagnostics(label)

    private fun exportSpeechAudio() {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    java.io.File(cacheDir, "latest-jarvis-speech.wav").takeIf { it.isFile }?.readBytes()
                }
                if (bytes == null) {
                    android.widget.Toast.makeText(this@MainActivity, "No completed reply audio yet", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pendingSpeechAudio = bytes // Snapshot before the chooser; later turns may replace the cache.
                    speechAudioExport.launch("jarvis-speech-${System.currentTimeMillis()}.wav")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "Could not read reply audio: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyDiagnostics(transcript: List<ChatEntry>) {
        // The runtime ring contains the latest call's ASR, inference and playback events.
        // Comparison archives and full chat histories do not belong in a call failure report.
        val diagnostics = "Jarvis OS V2 — latest Voice Call diagnostics\n\n${diagnosticRecorder.snapshot()}\n\n${com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.diagnostics()}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis diagnostics", diagnostics))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
        sessionPreferences.edit()
            .putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
            .apply()
        super.onSaveInstanceState(outState)
    }

    
    override fun onResume() {
        super.onResume()
        runtime.activityVisible = true
    }
    override fun onPause() {
        runtime.activityVisible = false
        super.onPause()
    }
    override fun onDestroy() {
        wakeTestJob?.cancel()
        runtime.detachUi()
        // Voice jobs and models belong to the service runtime, including during Activity recreation.
        super.onDestroy()
    }

    /** Reset the native conversation without tearing down the initialized Engine. */
    internal suspend fun resetNativeConversation() {
        conversationEngine?.resetConversation()
        nativeConversationHasContext = false
        conversationCharacters = 0
    }

    private fun selectAiModel(spec: com.battlesbudz.jarvis.v2.ai.LocalModelSpec): String? {
        if (voiceSessionArmed || voiceSessionController.currentCallId() != null ||
            voiceTurnJob?.isCompleted == false || ConversationWork.activeJobs.get() != 0 ||
            wakeTestJob?.isActive == true) {
            return "End the Jarvis session and any tests before switching AI models."
        }
        if (!modelStore.tryBeginModelOperation()) return "Wait for model setup or testing to finish."
        return try {
            conversationEngine?.close()
            conversationEngine = null
            nativeConversationHasContext = false
            conversationCharacters = 0
            modelStore.selectModel(spec)
            diagnosticRecorder.recordImportant("AI model selected: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not switch models: ${error.message}"
        } finally {
            modelStore.endModelOperation()
        }
    }

    private fun deleteAiModel(spec: com.battlesbudz.jarvis.v2.ai.LocalModelSpec): String? {
        if (voiceSessionArmed || voiceSessionController.currentCallId() != null ||
            voiceTurnJob?.isCompleted == false || ConversationWork.activeJobs.get() != 0 ||
            wakeTestJob?.isActive == true) {
            return "End the Jarvis session and any tests before deleting AI models."
        }
        if (!modelStore.tryBeginModelOperation()) return "Wait for model setup or testing to finish."
        return try {
            conversationEngine?.close()
            conversationEngine = null
            nativeConversationHasContext = false
            conversationCharacters = 0
            modelStore.deleteModel(spec)
            diagnosticRecorder.recordImportant("AI model deleted: ${spec.id}")
            null
        } catch (error: Exception) {
            "Could not delete model: ${error.message}"
        } finally {
            modelStore.endModelOperation()
        }
    }

    private fun runModelSmokeTest(
        report: (String) -> Unit,
        onFinished: ((String) -> Unit)? = null
    ) {
        if (!modelStore.tryBeginModelOperation()) {
            report("A model test is still finishing. Please try again in a moment.")
            onFinished?.invoke("A model test is still finishing. Please try again in a moment.")
            return
        }
        val smokeTestJob = lifecycleScope.launch(Dispatchers.Default) {
            mainHandler.post { report("Loading ${modelStore.selectedModel().id}…") }
            var gemma: LiteRtLmEngine? = null
            var smokeTestSucceeded = false
            var finalMessage: String? = null
            try {
                modelStore.markSmokeTestStarted()
                check(modelStore.verifyIntegrity(modelStore.selectedModel())) {
                    "The selected model file changed or failed integrity verification. Re-import it."
                }
                conversationEngine?.close()
                conversationEngine = null
                nativeConversationHasContext = false
                gemma = LiteRtLmEngine(
                    modelStore.selectedModel().id,
                    modelStore.fileFor(modelStore.selectedModel()).path,
                    cacheDir.path,
                    useGpu = modelStore.selectedModel().recommendedGpu,
                    tools = if (modelStore.selectedModel().supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                    visionEnabled = modelStore.selectedModel().supportsVision,
                    audioEnabled = modelStore.selectedModel().supportsAudio
                )
                gemma.initialize()
                val probe = gemma.generate(
                    "Reply with exactly GEMMA_PR1_OK and nothing else.",
                    onToken = {}
                )
                check(probe.text.trim() == "GEMMA_PR1_OK") {
                    "The selected model file did not pass its identity probe."
                }
                smokeTestSucceeded = true
            } catch (error: Throwable) {
                finalMessage = "Selected model test failed: ${error.message ?: "unknown error"}"
            } finally {
                gemma?.close()
                if (smokeTestSucceeded) {
                    modelStore.markSmokeTestPassed()
                    finalMessage = "${modelStore.selectedModel().id} initialized successfully."
                }
                finalMessage?.let { message ->
                    mainHandler.post {
                        report(message)
                        onFinished?.invoke(message)
                    }
                }
            }
        }
        smokeTestJob.invokeOnCompletion { modelStore.endModelOperation() }
    }

    private fun downloadGemmaAndTest(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        report: (String) -> Unit,
        onFinished: (String) -> Unit
    ) {
        val workName = "jarvis-local-model-setup"
        val request = OneTimeWorkRequestBuilder<com.battlesbudz.jarvis.v2.voice.JarvisModelSetupWorker>()
            .setInputData(androidx.work.workDataOf("model_id" to modelStore.selectedModel().id))
            .addTag(workName)
            .build()
        val workManager = WorkManager.getInstance(applicationContext)
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        lifecycleScope.launch {
            var terminal: WorkInfo? = null
            workManager.getWorkInfosForUniqueWorkFlow(workName)
                .takeWhile { infos ->
                    val info = infos.firstOrNull()
                    if (info != null) {
                        val stage = info.progress.getString("stage")
                        val downloaded = info.progress.getLong("downloaded", 0L)
                        val total = info.progress.getLong("total", -1L)
                        if (!stage.isNullOrBlank()) report(stage)
                        if (downloaded > 0L) onProgress(downloaded, total)
                        if (info.state.isFinished) {
                            terminal = info
                            false
                        } else true
                    } else true
                }
                .collect { }
            val result = terminal
            if (result?.state == WorkInfo.State.SUCCEEDED) {
                runModelSmokeTest(report, onFinished)
            } else {
                val message = result?.outputData?.getString("error")
                    ?: "Jarvis model setup did not complete."
                report(message)
                onFinished(message)
            }
        }
    }

    private fun importModel(
        uri: Uri,
        spec: com.battlesbudz.jarvis.v2.ai.LocalModelSpec,
        report: (String) -> Unit
    ) {
        val importJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = modelStore.importModel(uri, spec)
            withContext(Dispatchers.Main) {
                if (result.isSuccess && spec.id == modelStore.selectedModel().id) {
                    conversationEngine?.close()
                    conversationEngine = null
                    nativeConversationHasContext = false
                }
                report(result.fold(
                    { "Model imported successfully." },
                    { error ->
                        val message = error.message ?: "unknown error"
                        "Import failed: $message"
                    }
                ))
            }
        }
        if (spec.id == modelStore.selectedModel().id) {
            importJob.invokeOnCompletion {
                conversationEngine?.close()
                conversationEngine = null
                nativeConversationHasContext = false
            }
        }
    }

    internal fun cleanSpeechText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forSpeech(text)
    internal fun cleanAssistantText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forDisplay(text)

}
