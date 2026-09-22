package com.battlesbudz.jarvis.v2

import com.battlesbudz.jarvis.v2.conversation.ConversationPolicy
import com.battlesbudz.jarvis.v2.conversation.ConversationWork
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.MobileActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.conversation.runConversationInternal
import com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore
import com.battlesbudz.jarvis.v2.voice.AndroidAudioInput
import com.battlesbudz.jarvis.v2.voice.AsrModelStore
import com.battlesbudz.jarvis.v2.voice.SileroSpeechDetector
import com.battlesbudz.jarvis.v2.voice.Pcm16Signal
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceTurnCoordinator
import com.battlesbudz.jarvis.v2.voice.TtsModelStore
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Application-context runtime. The foreground service owns voice execution; UI only observes. */
internal class JarvisRuntime private constructor(context: android.content.Context) : android.content.ContextWrapper(context) {
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal lateinit var modelStore: ModelStore
    internal var conversationEngine: LiteRtLmEngine? = null
    internal var conversationJob: Job? = null
    internal var conversationCharacters = 0
    // The full transcript and rolling summary live in the app. This flag only
    // describes whether the current native Conversation has received that
    // app-managed context capsule.
    internal var nativeConversationHasContext = false
    private var contextCallId: String? = null
    internal val shortTermContext = ShortTermConversationContext()
    internal val referenceGrounding = ReferenceGroundingClient()
    internal val factualityVerifier = com.battlesbudz.jarvis.v2.ai.FactualityVerifier()
    internal val turnOrchestrator = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(referenceGrounding)
    internal val promptBuilder = com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder(shortTermContext)
    internal val actionIntentRouter = com.battlesbudz.jarvis.v2.actions.ActionIntentRouter()
    internal lateinit var sessionPreferences: android.content.SharedPreferences
    internal lateinit var diagnosticRecorder: com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
    internal val conversationHistory = com.battlesbudz.jarvis.v2.chat.ConversationHistory(
        getSharedPreferences("conversations", MODE_PRIVATE))
    internal val chatBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal lateinit var voiceCallStore: com.battlesbudz.jarvis.v2.voice.VoiceCallStore
    internal lateinit var voiceSessionController: VoiceSessionController
    internal lateinit var ttsComparisonStore: com.battlesbudz.jarvis.v2.voice.TtsComparisonStore
    internal lateinit var ttsModels: com.battlesbudz.jarvis.v2.voice.TtsModelStore
    internal val voicePlayback = kotlinx.coroutines.flow.MutableStateFlow(com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame())
    internal lateinit var asrComparisonStore: com.battlesbudz.jarvis.v2.voice.AsrComparisonStore
    @Volatile internal var activeVoiceCapture: AudioTurnCapture? = null
    @Volatile internal var voiceTurnJob: Job? = null
    internal var audioRecoveryAttempts = 0
    internal val returnToWakeCuePending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var voiceSessionArmed = false
        set(value) {
            field = value
            com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.armed.value = value
        }
    @Volatile internal var sessionReport: (String) -> Unit = {}

    @Volatile internal var activeVoiceOutput: PiperVoiceOutput? = null
    init {
        modelStore = ModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
        voiceCallStore = com.battlesbudz.jarvis.v2.voice.CoalescingVoiceCallStore(
            com.battlesbudz.jarvis.v2.chat.ConversationVoiceCallStore(
                SharedPreferencesVoiceCallStore(getSharedPreferences("voice_calls", MODE_PRIVATE)), conversationHistory),
            onFailure = { diagnosticRecorder.recordImportant("Voice checkpoint failed: ${it.javaClass.simpleName}") })
        voiceSessionController = VoiceSessionController(voiceCallStore)
        asrComparisonStore = com.battlesbudz.jarvis.v2.voice.AsrComparisonStore(getSharedPreferences("asr_comparison", MODE_PRIVATE))
        ttsComparisonStore = com.battlesbudz.jarvis.v2.voice.TtsComparisonStore(getSharedPreferences("tts_comparison", MODE_PRIVATE))
        ttsModels = com.battlesbudz.jarvis.v2.voice.TtsModelStore(applicationContext)
        val installedPackage = packageManager.getPackageInfo(packageName, 0)
        diagnosticRecorder = com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder(sessionPreferences,
            "${installedPackage.versionName} (${installedPackage.longVersionCode})")
        diagnosticRecorder.restore()
        diagnosticRecorder.recordPreviousProcessExit(applicationContext)
        shortTermContext.restoreSummary(sessionPreferences.getString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, null))
        runtimeScope.launch {
            for (control in com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.controls) {
                if (!voiceSessionArmed) continue
                val ui = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
                if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.RESUME) ui.paused.value = false
                else {
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE) ui.paused.value = true
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE ||
                        control == com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) {
                        com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
                        runtimeScope.launch { callResources.closeMicrophone() }
                    }
                    activeVoiceOutput?.stopSpeaking()
                    voiceTurnJob?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(control))
                }
            }
        }
    }
    @Volatile internal var activityVisible = false
    @Volatile private var transcriptListener: (String, String, Boolean) -> Unit = { _, _, _ -> }
    @Volatile private var finishedListener: (String) -> Unit = {}
    fun attachUi(report: (String) -> Unit, transcript: (String, String, Boolean) -> Unit, finished: (String) -> Unit) {
        sessionReport = report; transcriptListener = transcript; finishedListener = finished
    }
    fun detachUi() {
        sessionReport = {}; transcriptListener = { _, _, _ -> }; finishedListener = {}
    }
    fun arm() {
        audioRecoveryAttempts = 0
        returnToWakeCuePending.set(false)
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
        voiceSessionArmed = true
        startVoiceDiagnostics("Jarvis session — awaiting wake word")
    }
    fun sendChat(text: String, attachment: com.battlesbudz.jarvis.v2.chat.ChatAttachment? = null): String? {
        if (text.isBlank() && attachment == null) return "Write a message first."
        if (attachment != null && !com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.accepts(modelStore.selectedModel(), attachment.kind))
            return "The selected download does not support this attachment. Choose a compatible model or remove it."
        val userText = text.trim().ifBlank { if (attachment?.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO)
            "Transcribe this audio." else "Describe this image." }
        if (text.length > ConversationPolicy.MAX_USER_PROMPT_CHARS) return "That message is too long. Please send it in smaller parts."
        if (chatBusy.value || voiceSessionArmed || voiceTurnJob?.isCompleted == false ||
            conversationJob?.isCompleted == false || ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
            return "Wait for the current response or voice session to finish."
        if (!modelStore.isUsable() || !modelStore.smokeTestPassed()) return "Set up and test a model first."
        val history = conversationHistory.context()
        val threadId = conversationHistory.current.value.id
        val replyId = java.util.UUID.randomUUID().toString()
        conversationHistory.appendUser(userText, attachment)
        conversationHistory.updateReply(threadId, replyId, "", false)
        chatBusy.value = true
        runtimeScope.launch {
            val response = StringBuilder()
            try {
                // Mode changes may leave a native voice session behind. App history is authoritative.
                shortTermContext.clear()
                resetNativeConversation()
                runConversationInternal(userText, history,
                    attachment?.takeIf { it.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE }?.let { android.net.Uri.parse(it.uri) },
                    audioUri = attachment?.takeIf { it.kind == com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO }?.let { android.net.Uri.parse(it.uri) },
                    onToken = { token -> synchronized(response) {
                        response.append(token)
                        conversationHistory.updateReply(threadId, replyId, response.toString(), false)
                    } },
                    onComplete = { answer ->
                        conversationHistory.updateReply(threadId, replyId, answer, true)
                    })
                conversationJob?.join()
            } catch (error: Exception) {
                conversationHistory.updateReply(threadId, replyId,
                    response.toString().ifBlank { "The response was interrupted. Please try again." }, false)
            } finally {
                // Completion is posted before this callback on the same main queue.
                mainHandler.post { chatBusy.value = false }
            }
        }
        return null
    }

    fun selectConversation(id: String? = null): String? {
        if (chatBusy.value || voiceSessionArmed || voiceTurnJob?.isCompleted == false ||
            ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
            return "Finish the current response or voice session first."
        if (id == null) conversationHistory.newConversation() else conversationHistory.select(id)
        shortTermContext.clear()
        turnOrchestrator.reset()
        nativeConversationHasContext = false
        sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
        return null
    }

    private val callResources by lazy {
        com.battlesbudz.jarvis.v2.voice.VoiceCallResources(
            createAudio = { communication ->
                val useCommunication = communication && android.os.Build.VERSION.SDK_INT >= 31
                val manager = getSystemService(android.media.AudioManager::class.java)
                val input = AndroidAudioInput(runtimeScope, echoCancellation = true, noiseSuppression = true,
                    communicationInput = useCommunication, audioManager = manager,
                    onLevel = { com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.level.value = it },
                    log = { diagnosticRecorder.recordImportant("Microphone: $it") })
                val source = if (useCommunication) com.battlesbudz.jarvis.v2.voice.RoutedAudioInput(input, acquire = {
                    val route = com.battlesbudz.jarvis.v2.voice.CommunicationAudioSession.openSpeaker(manager) {
                        diagnosticRecorder.recordImportant("Voice call route: $it")
                    }
                    try {
                        route.awaitReady()
                        diagnosticRecorder.recordImportant("Voice call route: policy=communication_speaker_v1 source=VOICE_COMMUNICATION usage=VOICE_COMMUNICATION")
                        route
                    } catch (error: Throwable) { route.close(); throw error }
                }) else input
                com.battlesbudz.jarvis.v2.voice.VoiceAudioSession(
                    source, runtimeScope,
                    log = { diagnosticRecorder.recordImportant("Voice capture ownership: $it") })
            },
            createModels = {
                com.battlesbudz.jarvis.v2.voice.VoiceModelSession {
                    diagnosticRecorder.recordImportant("Voice model ownership: $it")
                }
            })
    }
    @Volatile private var latestStatus = "Preparing microphone…"
    private val pendingVoiceCorrection = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn?>(null)
    private val resumeCommandCue = java.util.concurrent.atomic.AtomicBoolean(false)
    fun onMicrophoneInterruption(interrupted: Boolean, reason: String) {
        activeVoiceOutput?.setInterrupted(interrupted)
        if (interrupted) {
            activeVoiceCapture?.yieldMicrophone()
            runtimeScope.launch { callResources.closeMicrophone(com.battlesbudz.jarvis.v2.voice.MicrophoneBusyException()) }
        }
        if (interrupted) runtimeScope.launch(Dispatchers.IO) {
            runCatching { voiceSessionController.flushCheckpoint() }
                .onFailure { diagnosticRecorder.recordImportant("Voice checkpoint handoff flush failed: ${it.javaClass.simpleName}") }
        }
        diagnosticRecorder.recordImportant("Microphone ${if (interrupted) "suspended" else "available"}: call=${voiceSessionController.currentCallId()} phase=$latestStatus $reason")
        if (!voiceSessionArmed) return
        if (interrupted) {
            if (voiceSessionController.currentCallId() == null) returnToWakeCuePending.set(true)
            else resumeCommandCue.set(true)
            com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus("Paused — another app has microphone priority; session retained.")
        } else if (!com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
            com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus(latestStatus)
        }
    }
    fun runVoiceTurn() {
        if (!voiceSessionArmed || voiceTurnJob?.isCompleted == false) return
        fun report(message: String) { sessionReport(message) }
        fun onTranscript(role: String, text: String, complete: Boolean) { transcriptListener(role, text, complete) }
        fun onFinished(message: String) { finishedListener(message) }
        val comparison = com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.take(java.util.UUID.randomUUID().toString())
        val asrEngine = comparison?.request?.path?.captureEngine ?: com.battlesbudz.jarvis.v2.voice.AsrEngine.selected(applicationContext)
        val ttsEngine = ttsComparisonStore.selectedEngine()
        val asrTurnId = comparison?.id ?: java.util.UUID.randomUUID().toString()
        val turnTrace = com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace(asrTurnId)
        val replyLatency = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.diagnostics.TurnLatency?>(null)
        val replyTtsMetrics = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.TtsSessionMetrics?>(null)
        val speechEndToReplyMs = java.util.concurrent.atomic.AtomicLong(-1)
        val finalReadyAt = java.util.concurrent.atomic.AtomicLong(0)
        val speechEndedAt = java.util.concurrent.atomic.AtomicLong(0)
        val firstPlayback = java.util.concurrent.atomic.AtomicBoolean(true)
        voiceTurnJob = runtimeScope.launch(Dispatchers.Default) {
            var operationOwned = false
            var preparation: com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput? = null
            var capture: AudioTurnCapture? = null
            var microphone: com.battlesbudz.jarvis.v2.voice.AudioInput? = null
            var expectedResourceCall: String? = null
            var preserveCaptureOnCancellation = false
            var voiceOutput: PiperVoiceOutput? = null
            val finalSpeechDelivery = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.SpeechDelivery?>(null)
            val speechChunks = Channel<String>(Channel.UNLIMITED)
            var speechJob: Job? = null
            var microphoneYielded = false
            val hadActiveCall = voiceSessionController.currentCallId() != null
            var wokeThisTurn = false
            var finalMessage = "Voice Call turn failed."
            fun status(message: String) {
                latestStatus = message
                if (!com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.interrupted.value) {
                    com.battlesbudz.jarvis.v2.voice.VoiceCallService.updateStatus(message)
                    mainHandler.post { report(message) }
                }
            }
            try {
                while (com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
                    status("Paused — microphone off. Tap Resume microphone to listen again.")
                    kotlinx.coroutines.delay(250)
                }
                com.battlesbudz.jarvis.v2.voice.MicrophoneInterruptionMonitor.awaitAvailable()
                check(ConversationWork.activeJobs.get() == 0 && modelStore.tryBeginModelOperation()) {
                    "Another model operation is still finishing. Please try again in a moment."
                }
                operationOwned = true
                voicePlayback.value = com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame()
                status("Preparing speech recognition…")
                val asrDirectory = asrEngine.prepare(applicationContext, ::status)
                diagnosticRecorder.record("Voice ASR selected engine=${asrEngine.id} model=${asrEngine.modelVersion} turn=$asrTurnId")
                check(modelStore.verifyIntegrity(modelStore.selectedModel())) { "The selected model failed integrity verification." }
                val selectedSpec = modelStore.selectedModel()
                val comparisonLoadStarted = System.nanoTime()
                comparison?.put("llm_reused", conversationEngine != null && conversationEngine?.modelId == selectedSpec.id && conversationEngine?.audioEnabled == selectedSpec.supportsAudio)
                if (conversationEngine == null || conversationEngine?.modelId != selectedSpec.id ||
                    conversationEngine?.audioEnabled != selectedSpec.supportsAudio) {
                    conversationEngine?.close()
                    conversationEngine = null
                    val created = LiteRtLmEngine(
                        modelStore.selectedModel().id, modelStore.fileFor(modelStore.selectedModel()).path,
                        cacheDir.path, useGpu = selectedSpec.recommendedGpu,
                        tools = if (selectedSpec.supportsTools) MobileActionToolDefinitions.all() else emptyList(),
                        audioEnabled = selectedSpec.supportsAudio
                    )
                    try { created.initialize() } catch (error: Throwable) { created.close(); throw error }
                    conversationEngine = created
                }
                val engine = requireNotNull(conversationEngine)
                comparison?.put("llm_setup_ms", (System.nanoTime() - comparisonLoadStarted) / 1_000_000)
                comparison?.put("llm", engine.modelId)
                comparison?.put("build", BuildConfig.VERSION_NAME)
                comparison?.put("source_commit", BuildConfig.SOURCE_COMMIT)
                comparison?.put("capture_asr", if (comparison?.request?.path?.usesAudio == true) "none_Gemma_audio" else asrEngine.id)
                comparison?.put("barge_asr", asrEngine.id)
                comparison?.put("thermal_before", getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
                comparison?.put("media_volume", getSystemService(android.media.AudioManager::class.java).getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                comparison?.put("audio_mode", getSystemService(android.media.AudioManager::class.java).mode)
                comparison?.put("context_policy", "fresh_prompt_each_trial_models_retained_by_normal_call_ownership")
                if (comparison?.request?.path?.usesAudio == true) check(engine.modelId.startsWith("Gemma", ignoreCase = true) && engine.audioEnabled) {
                    "Choose an audio-capable Gemma model before testing Gemma ASR."
                }
                resetNativeConversation()
                conversationCharacters = 0
                val ttsDirectory = ttsModels.ensureReady(ttsEngine, ::status)
                val followupBoundary = callResources.consumeFollowupBoundary()
                var input = callResources.borrowMicrophone("command", followupBoundary,
                    communication = voiceSessionController.currentCallId() != null)
                microphone = input
                if (voiceSessionController.currentCallId() == null) {
                    val wakeDirectory = com.battlesbudz.jarvis.v2.voice.WakeWordModelStore(applicationContext).ensureReady(::status)
                    com.battlesbudz.jarvis.v2.voice.PassiveWakeListener(wakeDirectory,
                        log = { diagnosticRecorder.record("Voice wake: $it") },
                        onReady = {
                            status("Waiting for Hey Jarvis — microphone active")
                            if (returnToWakeCuePending.getAndSet(false)) launch {
                                com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                                    com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.WAKE_LISTENING,
                                    log = { diagnosticRecorder.recordImportant(it) })
                            }
                        }).use { wake ->
                        input.start()
                        status("Preparing wake detector — microphone warming up…")
                        wake.awaitWake(input)
                    }
                    voiceSessionController.beginCall(conversationHistory.current.value.id).also { startVoiceDiagnostics("Voice Call ${it.id}") }
                    input.stop()
                    input = callResources.borrowMicrophone("command", communication = true)
                    microphone = input
                    diagnosticRecorder.recordImportant("Wake word detected: Hey Jarvis. ASR and call audio start now.")
                    wokeThisTurn = true
                    status("Hey Jarvis detected — getting ready to listen…")
                }
                val expectedCallId = voiceSessionController.currentCallId()
                    ?: throw kotlinx.coroutines.CancellationException("voice_call_ended_during_preparation")
                expectedResourceCall = expectedCallId
                val resourceKey = "$expectedCallId:${asrEngine.id}:${ttsEngine.id}"
                val models = callResources.modelsFor(resourceKey)
                if (contextCallId != expectedCallId) {
                    shortTermContext.clear()
                    turnOrchestrator.reset()
                    sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).apply()
                    contextCallId = expectedCallId
                    diagnosticRecorder.recordImportant("Voice context boundary: call=$expectedCallId summary=cleared subject=cleared nativeConversation=fresh")
                }
                val provenance = voiceSessionController.contextProvenance() +
                    " sharedConversationId=${conversationHistory.current.value.id} sharedThreadEntries=${conversationHistory.current.value.messages.size}"
                var submissionIndex = 0
                engine.onPromptSubmitted = { submitted, audioSize ->
                    comparison?.log("prompt audioBytes=$audioSize text=$submitted")
                    diagnosticRecorder.recordInferencePrompt(
                        "turn=$asrTurnId submission=${++submissionIndex} model=${engine.modelId} " +
                            "mode=${if (audioSize > 0) "audio_text" else "text"} audioBytes=$audioSize " +
                            "audioCorrectionCount=not_observable promptChars=${submitted.length}\n" +
                            provenance + "\n${engine.inputContextDescription()}\nsummaryChars=${shortTermContext.summaryForDiagnostics()?.length ?: 0}\n" +
                            "--- Exact submitted text begins ---\n$submitted\n--- Exact submitted text ends ---")
                }
                if (comparison != null) { shortTermContext.clear(); turnOrchestrator.reset() }
                val voiceHistory = if (comparison != null) emptyList() else
                    (conversationHistory.context(excludingCall = expectedCallId) +
                        voiceSessionController.conversationContext().map { ChatEntry(it.role, it.text) }).takeLast(24)
                // Keep the normal resident interruption recognizer available for audio-only input paths.
                if (comparison?.request?.path?.usesAudio == true) asrEngine.create(asrDirectory, modelSession = models).close()
                diagnosticRecorder.recordSummary("Voice TTS turn=$asrTurnId engine=${ttsEngine.id} " +
                    "speechPolicy=piper-natural-v1")
                val output = PiperVoiceOutput(ttsDirectory.path, engine = ttsEngine,
                    modelSession = models,
                    deliveryLedger = com.battlesbudz.jarvis.v2.voice.SpeechDeliveryLedger(asrTurnId) { delivery ->
                        finalSpeechDelivery.set(delivery)
                        voiceSessionController.updateDelivery(expectedCallId, delivery)
                        diagnosticRecorder.recordImportant("Voice delivery turn=$asrTurnId state=${delivery.state} " +
                            "completedChars=${delivery.deliveredText.length} partialSpan=${delivery.partialSpanIndex} " +
                            "playedFrames=${delivery.playedFrames} precision=segment_frames")
                    },
                    onPlaybackEnded = { callResources.playbackEnded(System.nanoTime() / 1_000_000) },
                    acknowledgeDelays = true,
                    playbackVolume = {
                        val manager = getSystemService(android.media.AudioManager::class.java)
                        val stream = com.battlesbudz.jarvis.v2.voice.CallAudioRouting.stream
                        "${manager.getStreamVolume(stream)}/${manager.getStreamMaxVolume(stream)} muted=${manager.isStreamMute(stream)}"
                    },
                    audioTrace = com.battlesbudz.jarvis.v2.voice.SpeechAudioTrace(
                        java.io.File(cacheDir, "latest-jarvis-speech.wav"), asrTurnId,
                        log = { diagnosticRecorder.recordImportant(it) }),
                    onPlayback = { voicePlayback.value = it },
                    onMetrics = {
                        comparison?.put("tts_metrics", it.toString())
                        replyTtsMetrics.set(it)
                        diagnosticRecorder.recordTurnEvidence(asrTurnId, "tts", "engine=${ttsEngine.id} firstTextToPcmMs=${it.firstTextToPcmMs} " +
                            "synthesisMs=${it.synthesisMs} audioMs=${it.audioMs} underruns=${it.underruns}")
                        ttsComparisonStore.add(ttsEngine, "voice-call", asrTurnId, it)
                        diagnosticRecorder.recordSummary("Voice TTS turn=$asrTurnId loadMs=${it.loadMs} " +
                            "firstTextToPcmMs=${it.firstTextToPcmMs} firstTextToPlaybackMs=${it.firstTextToPlaybackMs} " +
                            "synthesisMs=${it.synthesisMs} audioMs=${it.audioMs} threads=${it.threads} " +
                            "underruns=${it.underruns}")
                    },
                    log = {
                        comparison?.log("tts $it")
                        if (it.startsWith("tts_session_finished"))
                            diagnosticRecorder.recordTurnEvidence(asrTurnId, if (it.startsWith("tts_session")) "supply" else "pcm", it)
                        if (it.startsWith("acknowledgement_") ||
                            it.startsWith("audio_underrun") ||
                            it.startsWith("audio_supply_gap")) diagnosticRecorder.recordSummary("Voice TTS turn=$asrTurnId: $it")
                        if (it.startsWith("audio_underrun") || it.startsWith("audio_supply_gap") ||
                            it.startsWith("audio_startup_buffer")) diagnosticRecorder.recordImportant("Voice TTS: $it")
                        else diagnosticRecorder.record("Voice TTS: $it")
                    })
                voiceOutput = output
                activeVoiceOutput = output
                output.setInterrupted(com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.interrupted.value)
                // Preload Piper while listening; this channel stays empty until final validation.
                speechJob = launch(Dispatchers.Default) {
                    try {
                        output.speak(speechChunks.receiveAsFlow()) {
                            if (firstPlayback.compareAndSet(true, false) && finalReadyAt.get() != 0L) {
                                comparison?.mark("answer_audio")
                                turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.FIRST_REPLY_AUDIO)
                                asrComparisonStore.update(asrTurnId, "final_to_playback_start_ms",
                                    (System.nanoTime() - finalReadyAt.get()) / 1_000_000)
                                if (speechEndedAt.get() != 0L) {
                                    val elapsed = System.nanoTime() / 1_000_000 - speechEndedAt.get()
                                    speechEndToReplyMs.set(elapsed)
                                    asrComparisonStore.update(asrTurnId, "speech_end_to_playback_ms", elapsed)
                                    diagnosticRecorder.recordImportant("Voice latency: speech_end_to_playback_ms=$elapsed turn=$asrTurnId")
                                }
                            }
                            status("Jarvis is speaking…")
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Throwable) {
                        comparison?.put("tts_error", error.message ?: error.javaClass.simpleName)
                        diagnosticRecorder.record("Voice TTS failure: ${error.message}")
                        status("Voice playback failed: ${error.message}")
                    }
                }
                val incremental = com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput(
                    this, promptBuilder.voiceInputPrefix(voiceHistory, (selectedSpec.contextTokens ?: 4096) < 2048), engine::createVoicePrefillSession,
                    canPrefill = {
                        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29)
                            getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
                        val allowed = selectedSpec.incrementalGemmaInput && models.workScheduler.admitPrefill(input.bufferedAudioMs, thermal)
                        diagnosticRecorder.record("Voice input scheduler: allowed=$allowed reason=${models.workScheduler.reason} " +
                            "thermal=$thermal cutoff=5 backlogMs=${input.bufferedAudioMs}")
                        allowed
                    }, log = {
                        comparison?.log("incremental $it")
                        diagnosticRecorder.recordSummary("Voice incremental turn=$asrTurnId $it")
                        if (it.startsWith("input_final"))
                            diagnosticRecorder.recordTurnEvidence(asrTurnId, it.substringBefore(' '), it)
                    })
                preparation = incremental
                diagnosticRecorder.recordSummary("Voice input: speaker_identity=disabled interruption_policy=recognized_non_echo_words")
                val activeCapture = AudioTurnCapture(
                    com.battlesbudz.jarvis.v2.voice.QuietSpeechAudioInput(input, maxGain = 1.0, log = {
                        diagnosticRecorder.record("Voice input: $it")
                    }), this,
                    allowAudioOnlyTurns = true,
                    guardFollowupSpeech = followupBoundary != null,
                    createDetector = { SileroSpeechDetector.create(assets) },
                    log = {
                        comparison?.log("capture $it")
                        if (it.startsWith("followup_speech_evidence") || it.startsWith("followup_candidate_rejected")) {
                            diagnosticRecorder.recordTurnEvidence(asrTurnId, "followup_speech", it)
                        }
                        if (it.startsWith("capture_endpoint_timing")) {
                            diagnosticRecorder.recordTurnEvidence(asrTurnId, "capture_endpoint", it)
                        }
                        if (it.startsWith("asr_recovery_") || it.startsWith("empty_speech_candidate") || it.startsWith("nonverbal_candidate")) {
                            diagnosticRecorder.recordImportant("Voice input: $it")
                        } else diagnosticRecorder.record("Voice input: $it")
                    },
                    onRecognitionRecovery = { recovering ->
                        status(if (recovering) "Retrying speech recognition…" else "Voice Call is listening — speak now.")
                    },
                    createTranscriber = if (comparison?.request?.path?.usesAudio == true) null else {
                        { asrEngine.create(asrDirectory, log = { diagnosticRecorder.recordSummary("Voice input: $it") }, modelSession = models) }
                    },
                    onMetrics = { metrics, text ->
                        comparison?.put("asr_metrics", metrics.toString())
                        comparison?.put("speech_start_to_asr_first_partial_ms", metrics.firstPartialAfterSpeechMs ?: org.json.JSONObject.NULL)
                        asrComparisonStore.add(asrTurnId, metrics, text, asrEngine)
                        capture?.lastSpeechAtMs?.let { speechEndedAt.set(it) }
                        diagnosticRecorder.recordSummary("Voice input summary: turn=$asrTurnId " +
                            "reason=${metrics.endpointReason} speech=${capture?.hasSpeech} chars=${text.length} " +
                            "partials=${metrics.partialUpdates} firstPartialMs=${metrics.firstPartialAfterSpeechMs} " +
                            "endpointMs=${metrics.endpointDetectionMs}")
                    },
                    onPartialTranscript = { text ->
                        if (text.isNotBlank()) comparison?.mark("asr_first_partial")
                        comparison?.log("partial atMs=${System.nanoTime() / 1_000_000} text=$text")
                        incremental.submit(text)
                        mainHandler.post {
                            if (activeVoiceCapture === capture) onTranscript("You", text, false)
                        }
                    }
                )
                capture = activeCapture
                activeVoiceCapture = activeCapture
                val correction = pendingVoiceCorrection.getAndSet(null)
                comparison?.put("input_is_interruption_correction", correction != null)
                comparison?.mark("capture_start")
                if (correction == null) activeCapture.start()
                if (correction == null) turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.MICROPHONE_READY)
                val callAudioManager = getSystemService(android.media.AudioManager::class.java)
                val callStream = com.battlesbudz.jarvis.v2.voice.CallAudioRouting.stream
                diagnosticRecorder.recordImportant("Voice call route: turn=$asrTurnId mode=${callAudioManager.mode} " +
                    "usage=${com.battlesbudz.jarvis.v2.voice.CallAudioRouting.usage} stream=$callStream " +
                    "volume=${callAudioManager.getStreamVolume(callStream)}/${callAudioManager.getStreamMaxVolume(callStream)}")
                comparison?.put("audio_mode", callAudioManager.mode)
                comparison?.put("playback_stream", callStream)
                comparison?.put("playback_volume", callAudioManager.getStreamVolume(callStream))
                if (comparison != null) check(!callAudioManager.isStreamMute(callStream) && callAudioManager.getStreamVolume(callStream) > 0) {
                    "Unmute call audio before comparing audible response timing."
                }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!voiceSessionController.setStateIfCurrent(expectedCallId, VoiceSessionState.ACTIVELY_LISTENING)) {
                    throw kotlinx.coroutines.CancellationException("voice_call_ended_during_capture_start")
                }
                status("Voice Call is listening — speak now.")
                if (correction == null && (wokeThisTurn || resumeCommandCue.getAndSet(false))) {
                    com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                        com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.COMMAND_READY,
                        log = { diagnosticRecorder.recordImportant(it) })
                    diagnosticRecorder.recordImportant("Wake acknowledged; command microphone ready.")
                }
                if (correction == null) activeCapture.awaitTurnCompletion()
                turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.RECOGNITION_FINALIZED)
                val endpointAt = System.nanoTime()
                finalReadyAt.set(endpointAt)
                val firstFinalToken = java.util.concurrent.atomic.AtomicBoolean(true)
                val audioBytes = correction?.wav ?: activeCapture.stop()
                comparison?.wav = audioBytes.copyOf()
                activeCapture.lastSpeechAtMs?.let { comparison?.mark("speech_end", it); speechEndedAt.set(it) }
                comparison?.mark("capture_final")
                if (correction == null) turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.CAPTURE_CONSUMER_RELEASED)
                kotlin.coroutines.coroutineContext.ensureActive()
                if (voiceSessionController.currentCallId() != expectedCallId) {
                    throw kotlinx.coroutines.CancellationException("voice_call_changed_during_recognition")
                }
                val asrTranscript = correction?.transcript ?: activeCapture.finalTranscript
                val audioIsComplete = correction?.audioIsComplete ?: activeCapture.audioIsComplete
                var recognitionIssue = correction?.recognitionIssue ?: activeCapture.recognitionIssue
                if (activeVoiceCapture === activeCapture) activeVoiceCapture = null
                status("Processing your Voice Call turn locally…")
                if (correction == null && !activeCapture.hasSpeech) {
                    diagnosticRecorder.record("Voice call ended reason=inactivity timeoutMs=20000")
                    voiceSessionController.end()
                    finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " no recognized speech for 20 seconds."
                    return@launch
                }
                // Final ASR text belongs on screen immediately, before pending input processing is joined.
                if (asrTranscript.isNotBlank()) mainHandler.post {
                    if (voiceSessionController.currentCallId() == expectedCallId) onTranscript("You", asrTranscript, true)
                }
                diagnosticRecorder.recordSummary("Voice recognition turn=$asrTurnId path=${if (correction == null) "normal" else "after_keyword"} " +
                    "engine=${asrEngine.id} asrChars=${asrTranscript.length} gemmaTranscriptionFallback=${asrTranscript.isBlank()} " +
                    "speechGate=${if (asrEngine == com.battlesbudz.jarvis.v2.voice.AsrEngine.MOONSHINE) "jarvis_vad_native_gate_bypassed_v1" else "engine_default"}")
                // The turn is confirmed. Cached acknowledgement can play while queued input
                // prefill finishes; it needs neither Gemma nor tool execution permission.
                if (asrTranscript.isNotBlank() &&
                    !com.battlesbudz.jarvis.v2.voice.VoiceStopRequest.matches(asrTranscript) &&
                    !com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(asrTranscript)) {
                    output.acknowledgeConfirmedTurn()
                }
                val sealStarted = System.nanoTime()
                incremental.seal()
                val preparedText = incremental.takeIf { asrTranscript.isNotBlank() && recognitionIssue == null }
                if (preparedText == null) incremental.close()
                turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.PREPARATION_SEALED)
                diagnosticRecorder.recordSummary("Voice pipeline turn=$asrTurnId stage=preparation_sealed " +
                    "workMs=${(System.nanoTime() - sealStarted) / 1_000_000} " +
                    "sinceEndpointMs=${(System.nanoTime() - endpointAt) / 1_000_000}")
                if (asrTranscript.isBlank() && !engine.audioEnabled && recognitionIssue == null) {
                    recognitionIssue = "selected_model_has_no_audio_fallback"
                    diagnosticRecorder.recordImportant("Voice recognition empty: model=${engine.modelId} action=clarify no_audio_submission=true")
                }
                val resolvedTranscript = if (comparison?.request?.path == com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT && recognitionIssue == null)
                    "Respond to the user's spoken request in the attached audio."
                else if (recognitionIssue != null) asrTranscript else com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.resolve(
                    asrTranscript, audioBytes
                ) { audio ->
                    turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.AUDIO_FALLBACK_STARTED)
                    output.updateWaitStage(com.battlesbudz.jarvis.v2.voice.DelayedAcknowledgement.Stage.RECOGNIZING)
                    status("Listening to your recorded speech with Gemma…")
                    diagnosticRecorder.recordImportant("Voice audio fallback: ${asrEngine.label} empty; Gemma receiving ${audio.size} bytes")
                    try {
                        kotlinx.coroutines.withTimeout(12_000L) {
                            engine.setToolsEnabled(false)
                            com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.retryEmptyAudio { attempt ->
                                resetNativeConversation()
                                diagnosticRecorder.recordImportant("Voice audio fallback: stage=submit attempt=$attempt deadlineMs=12000 toolsEnabled=false")
                                comparison?.put("asr_attempts", attempt)
                                comparison?.mark("asr_submit")
                                val heard = engine.generateAudio(
                                    com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.instructions, audio, { token ->
                                        if (token.isNotBlank()) comparison?.mark("asr_first_token")
                                    })
                                comparison?.put("asr_attempt_${attempt}_text", heard.text)
                                comparison?.put("asr_attempt_${attempt}_stream_events", heard.streamEvents)
                                comparison?.put("asr_attempt_${attempt}_tool_calls", heard.toolCalls.size)
                                comparison?.put("asr_attempt_${attempt}_duration_ms", heard.totalGenerationTimeMs)
                                diagnosticRecorder.recordImportant("Voice audio fallback: attempt=$attempt chars=${heard.text.length} " +
                                    "streamEvents=${heard.streamEvents} toolCalls=${heard.toolCalls.size} durationMs=${heard.totalGenerationTimeMs} " +
                                    "text=${heard.text.take(1000)}")
                                // Recognition never dispatches tools or speaks model output.
                                if (heard.toolCalls.isEmpty()) heard.text else ""
                            }
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        recognitionIssue = "audio_fallback_timeout"
                        status("I couldn't make out that request. Please say it again.")
                        diagnosticRecorder.recordImportant("Voice audio fallback: result=timeout action=clarify")
                        ""
                    } finally {
                        // Native cancellation joins its owner before resetting the conversation.
                        // Its cleanup can extend the deadline; never close a model concurrently.
                        resetNativeConversation()
                        turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.AUDIO_FALLBACK_FINISHED)
                    }
                }
                if (recognitionIssue == null &&
                    !com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.hasTranscript(resolvedTranscript) &&
                    !com.battlesbudz.jarvis.v2.voice.TranscriptContent.isSoundOnly(resolvedTranscript)) {
                    recognitionIssue = "audio_fallback_empty"
                    diagnosticRecorder.recordImportant("Voice audio fallback: result=empty action=clarify answer_generation=false")
                }
                comparison?.put("recognition_issue", recognitionIssue ?: "none")
                comparison?.put("resolved_transcript", resolvedTranscript)
                if (com.battlesbudz.jarvis.v2.voice.TranscriptContent.isSoundOnly(resolvedTranscript)) {
                    incremental.close()
                    diagnosticRecorder.recordImportant("Voice input: nonverbal_candidate ignored=true source=audio_fallback destination=none")
                    finalMessage = "Voice Call is listening — speak now."
                    return@launch
                }
                val transcript = com.battlesbudz.jarvis.v2.voice.TranscriptContent.speech(resolvedTranscript)
                if (recognitionIssue == null && comparison?.request?.path != com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT &&
                    com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.hasTranscript(transcript)) comparison?.mark("transcript_final")
                comparison?.put("resolved_transcript", transcript)
                comparison?.put("raw_asr", asrTranscript)
                comparison?.put("recognition_issue", recognitionIssue ?: "none")
                diagnosticRecorder.recordTurnEvidence(asrTurnId, "recognition_text",
                    "engine=${asrEngine.id} transcriptionFallback=${asrTranscript.isBlank()} audioComplete=$audioIsComplete\n" +
                        "asr=$asrTranscript\nresolved=$transcript")
                if (asrTranscript.isBlank()) {
                    diagnosticRecorder.recordImportant("Voice audio fallback finished: chars=${transcript.length} source=gemma")
                }
                if (recognitionIssue == null && com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(transcript)) {
                    incremental.close()
                    voiceSessionController.appendTranscript("You", transcript)
                    voiceSessionController.end()
                    if (transcript != asrTranscript) mainHandler.post { onTranscript("You", transcript, true) }
                    diagnosticRecorder.record("Voice call ended reason=spoken_goodbye")
                    finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " goodbye."
                    return@launch
                }
                if (recognitionIssue == null && com.battlesbudz.jarvis.v2.voice.VoiceStopRequest.matches(transcript)) {
                    incremental.close()
                    voiceSessionController.appendTranscript("You", transcript)
                    resetNativeConversation()
                    diagnosticRecorder.recordImportant("Voice control: stop_reply source=final_transcript call_remains_active=true")
                    finalMessage = "Voice Call is listening — speak now."
                    return@launch
                }
                asrComparisonStore.update(asrTurnId, "prepared", false)
                diagnosticRecorder.record("Voice ASR final\ntext=$transcript\naudioBytes=${audioBytes.size}\n" +
                    "prepared=false inputMode=incremental_text audioForAnswer=false")
                if (transcript != asrTranscript) mainHandler.post {
                    if (voiceSessionController.currentCallId() == expectedCallId) onTranscript("You", transcript, true)
                }
                output.acknowledgeConfirmedTurn()
                diagnosticRecorder.recordSummary("Voice pipeline turn=$asrTurnId stage=reply_dispatch " +
                    "sinceEndpointMs=${(System.nanoTime() - endpointAt) / 1_000_000}")
                val outcome = com.battlesbudz.jarvis.v2.voice.runInterruptibleReply(
                    reply = {
                        output.updateWaitStage(com.battlesbudz.jarvis.v2.voice.DelayedAcknowledgement.Stage.GENERATING)
                        turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.REPLY_DISPATCHED)
                        val coordinator = VoiceTurnCoordinator(voiceSessionController)
                        val response = coordinator.processTurn(transcript, replyId = asrTurnId) { onToken ->
                            val completed = CompletableDeferred<String>()
                            val streamed = StringBuilder()
                            fun recordFirstText(text: String) {
                                if (text.isNotBlank() && firstFinalToken.compareAndSet(true, false)) {
                                    output.updateWaitStage(com.battlesbudz.jarvis.v2.voice.DelayedAcknowledgement.Stage.SYNTHESIZING)
                                    turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.FIRST_REPLY_TEXT)
                                    val elapsedMs = (System.nanoTime() - endpointAt) / 1_000_000
                                    asrComparisonStore.update(asrTurnId, "final_to_first_text_ms", elapsedMs)
                                    if (speechEndedAt.get() != 0L) {
                                        asrComparisonStore.update(asrTurnId, "speech_end_to_first_text_ms",
                                            System.nanoTime() / 1_000_000 - speechEndedAt.get())
                                    }
                                    diagnosticRecorder.recordSummary("Voice latency: endpoint_to_first_text_ms=$elapsedMs turn=$asrTurnId")
                                }
                            }
                            val interruptionTest = com.battlesbudz.jarvis.v2.voice.VoiceInterruptionTest.requested(transcript)
                            if (recognitionIssue != null) {
                                incremental.close()
                                val clarification = if (recognitionIssue == "audio_fallback_timeout" || recognitionIssue == "audio_fallback_empty" || recognitionIssue == "selected_model_has_no_audio_fallback")
                                    "I couldn't make out that request, sir. Please say it again."
                                else "I couldn't retain that whole request reliably. Please repeat it in shorter parts, sir."
                                diagnosticRecorder.recordImportant("Voice input rejected reason=$recognitionIssue action=clarify tools=disabled")
                                recordFirstText(clarification)
                                onToken(clarification)
                                speechChunks.trySend(clarification)
                                completed.complete(clarification)
                            } else if (interruptionTest) {
                                incremental.close()
                                val passage = com.battlesbudz.jarvis.v2.voice.VoiceInterruptionTest.passage
                                diagnosticRecorder.recordImportant("Voice interruption test: started source=local_passage normal_call_pipeline=true")
                                recordFirstText(passage)
                                onToken(passage)
                                speechChunks.trySend(passage)
                                completed.complete(passage)
                            } else runConversationInternal(
                                prompt = transcript, history = voiceHistory, imageUri = null,
                                incrementalVoice = preparedText, voiceAudio = audioBytes, voiceAudioIsComplete = audioIsComplete,
                                comparison = comparison,
                                onLatency = { replyLatency.set(it) },
                                onActionResult = { name, message, succeeded ->
                                    voiceSessionController.recordReplyAction(expectedCallId, asrTurnId,
                                        com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome(name, message, succeeded))
                                },
                                onToken = { token ->
                                    recordFirstText(token)
                                    onToken(token)
                                    streamed.append(token)
                                    mainHandler.post { onTranscript("Jarvis", token, false) }
                                    speechChunks.trySend(cleanSpeechText(token))
                                },
                                onComplete = { text ->
                                    // Guarded/tool replies may arrive only through completion, with no token callback.
                                    recordFirstText(text)
                                    if (streamed.isBlank() && text.isNotBlank()) speechChunks.trySend(cleanSpeechText(text))
                                    completed.complete(text)
                                }
                            )
                            val text = completed.await()
                            if (!interruptionTest && recognitionIssue == null) conversationJob?.join()
                            mainHandler.post { onTranscript("Jarvis", text, true) }
                            com.battlesbudz.jarvis.v2.ai.GenerationResult(text, -1L, null)
                        }
                        voiceSessionController.updateReplyText(expectedCallId, asrTurnId, response.text,
                            finished = true, latency = replyLatency.get())
                        speechChunks.close()
                        speechJob?.join()
                        replyLatency.get()?.let { latency ->
                            val metrics = replyTtsMetrics.get()
                            voiceSessionController.updateReplyLatency(latency.copy(voice = ttsEngine.label,
                                speechEndToReplyMs = speechEndToReplyMs.get().takeIf { it >= 0 },
                                textToPcmMs = metrics?.firstTextToPcmMs,
                                textToPlaybackMs = metrics?.firstTextToPlaybackMs,
                                supplyGapMs = metrics?.supplyGapMs))
                        }
                        response
                    },
                    listen = { confirmed ->
                        com.battlesbudz.jarvis.v2.voice.ReplyVoiceCapture(applicationContext) {
                            comparison?.log("barge $it")
                            diagnosticRecorder.recordImportant("Voice interruption: $it")
                            if (it.startsWith("barge_natural_summary") || it.startsWith("barge_keyword_summary") || it.startsWith("barge_evidence_"))
                                diagnosticRecorder.recordTurnEvidence(asrTurnId, it.substringBefore(" "), it)
                        }.listen(output, asrDirectory, confirmed, asrEngine = asrEngine, trace = turnTrace,
                            inputFactory = { callResources.borrowMicrophone("reply", communication = true) }, modelSession = models, onPartialTranscript = { text ->
                            mainHandler.post {
                                if (activeVoiceOutput === output && voiceSessionArmed) onTranscript("You", text, false)
                            }
                        })
                    },
                    stopReply = {
                        output.stopSpeaking()
                        turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.PLAYBACK_STOP_REQUESTED)
                        speechJob?.cancel()
                        conversationJob?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(
                            com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY))
                        diagnosticRecorder.recordImportant("Voice reply interrupted by speech; call retained, action not replayed.")
                        status("Voice Call is listening — speak now.")
                    }
                )
                if (outcome is com.battlesbudz.jarvis.v2.voice.ReplyOutcome.Interrupted) {
                    comparison?.put("interrupted", true)
                    conversationJob?.join()
                    if (outcome.correction.wav.size > 44) pendingVoiceCorrection.set(outcome.correction)
                    finalMessage = "Voice reply interrupted; continuing the same call."
                    return@launch
                }
                val response = (outcome as com.battlesbudz.jarvis.v2.voice.ReplyOutcome.Finished<com.battlesbudz.jarvis.v2.ai.GenerationResult>).value
                audioRecoveryAttempts = 0
                finalMessage = "Voice Call turn complete. Heard: $transcript\nJarvis: ${response.text}"
            } catch (backlog: com.battlesbudz.jarvis.v2.voice.AudioBacklogException) {
                audioRecoveryAttempts++
                diagnosticRecorder.recordImportant("Audio buffer recovery attempt=$audioRecoveryAttempts max=2; incomplete command discarded.")
                runCatching { voiceSessionController.interrupt() }
                finalMessage = if (audioRecoveryAttempts <= 2)
                    com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " audio capture recovered; say Hey Jarvis again."
                else "Voice Call turn failed: audio capture repeatedly fell behind. Restart the session."
            } catch (busy: com.battlesbudz.jarvis.v2.voice.MicrophoneBusyException) {
                // Keep the same call/context. Only an unfinished user utterance is discarded.
                if (voiceSessionController.currentCallId() != null) resumeCommandCue.set(true)
                else returnToWakeCuePending.set(true)
                diagnosticRecorder.recordImportant("Microphone yielded during capture; call=${voiceSessionController.currentCallId()} retained=true partial_discarded=true")
                finalMessage = "Paused — microphone interrupted; previous listening mode retained."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (cancelled is com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation) {
                    microphoneYielded = true // Use the same cleanup-before-rearm path.
                    diagnosticRecorder.recordImportant("Voice control requested: ${cancelled.control}")
                    preserveCaptureOnCancellation = cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY
                    if (cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) {
                        runCatching { voiceSessionController.end() }
                    }
                    finalMessage = when (cancelled.control) {
                        com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE -> "Paused — microphone off; conversation retained."
                        com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY -> "Reply stopped — continuing Voice Call."
                        else -> com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " user control."
                    }
                } else {
                    diagnosticRecorder.recordImportant("Voice capture cancelled: ${cancelled.message ?: cancelled.javaClass.simpleName} cause=${cancelled.cause?.javaClass?.simpleName}:${cancelled.cause?.message} armed=$voiceSessionArmed phase=$latestStatus")
                    if (voiceSessionArmed && runtimeScope.isActive && cancelled.message != "resuming_saved_voice_call") {
                        // An unexpected child cancellation must not leave an armed call deaf.
                        // Intentional stop disarms first; saved-call replacement owns its own restart.
                        microphoneYielded = true
                        audioRecoveryAttempts++
                        finalMessage = if (audioRecoveryAttempts <= 2)
                            "Recovering interrupted voice capture…"
                        else "Voice Call turn failed: capture repeatedly cancelled. Restart the session."
                        diagnosticRecorder.recordImportant("Capture cancellation recovery attempt=$audioRecoveryAttempts max=2")
                    }
                    throw cancelled
                }
            } catch (error: Throwable) {
                diagnosticRecorder.record("Voice turn failed: ${error.stackTraceToString().take(4000)}")
                runCatching { voiceSessionController.interrupt() }
                finalMessage = "Voice Call turn failed: ${error.message ?: "unknown error"}"
            } finally {
                val cancelled = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != true
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (cancelled) { conversationJob?.cancel(); conversationJob?.join() }
                    try {
                        runCatching { capture?.stop() }
                        runCatching { microphone?.stop() }
                        preparation?.close()
                        conversationEngine?.onPromptSubmitted = { _, _ -> }
                    } finally {
                        speechChunks.close()
                        runCatching { voiceOutput?.stopSpeaking() }
                        speechJob?.cancel()
                        speechJob?.join()
                        runCatching { voiceSessionController.flushCheckpoint() }
                            .onFailure { diagnosticRecorder.recordImportant("Voice checkpoint flush failed: ${it.javaClass.simpleName}") }
                        runCatching { voiceOutput?.release() }
                        finalSpeechDelivery.get()?.let { turnOrchestrator.reconcileVoiceDelivery(it.deliveredText) }
                        if (activeVoiceOutput === voiceOutput) activeVoiceOutput = null
                        if (activeVoiceCapture === capture) activeVoiceCapture = null
                        val callEnded = !voiceSessionArmed || voiceSessionController.currentCallId() == null ||
                            (expectedResourceCall != null && voiceSessionController.currentCallId() != expectedResourceCall) ||
                            finalMessage.contains("turn failed", true)
                        try {
                            if (callEnded || com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value ||
                                cancelled && !preserveCaptureOnCancellation) callResources.closeMicrophone()
                            if (callEnded) {
                                com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
                                callResources.closeModels()
                            }
                        } finally { if (operationOwned) modelStore.endModelOperation() }
                    }
                }
                turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.TURN_FINISHED)
                comparison?.let {
                    it.mark("finished")
                    it.put("final_status", finalMessage)
                    it.put("turn_completed", !cancelled && finalMessage.startsWith("Voice Call turn complete."))
                    it.put("pipeline", org.json.JSONObject(turnTrace.snapshot()))
                    it.put("thermal_after", getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
                    it.put("speech_delivery", finalSpeechDelivery.get()?.toString() ?: "unavailable")
                    com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.finish(it)
                }
                val stages = org.json.JSONObject(turnTrace.snapshot())
                asrComparisonStore.update(asrTurnId, "pipeline_stage_ms", stages)
                diagnosticRecorder.recordSummary("Voice pipeline turn=$asrTurnId stageOffsetsMs=$stages clock=monotonic fillerExcluded=true")
                diagnosticRecorder.recordTurnEvidence(asrTurnId, "pipeline", "stageOffsetsMs=$stages")
                (voiceCallStore as? com.battlesbudz.jarvis.v2.voice.CoalescingVoiceCallStore)?.metrics()?.let {
                    diagnosticRecorder.recordSummary("Voice checkpoints scope=runtime_cumulative progressUpdates=${it.progressUpdates} " +
                        "coalescedUpdates=${it.coalescedUpdates} writes=${it.writes} writeMs=${it.writeMs} failures=${it.failures}")
                }
                if (voiceSessionArmed && (hadActiveCall || wokeThisTurn) && voiceSessionController.currentCallId() == null) {
                    // Announce the actual return to a ready detector, not each ASR turn or an unavailable microphone.
                    returnToWakeCuePending.set(true)
                }
                if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true || microphoneYielded) {
                    // Re-arm only after this job (including all children) has actually finished.
                    kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
                        mainHandler.post {
                            report(finalMessage)
                            onFinished(finalMessage)
                            if (voiceSessionArmed && !finalMessage.contains("turn failed", true)) {
                                // Runtime owns re-arming, independent of Compose rendering or visibility.
                                runVoiceTurn()
                            } else {
                                voiceSessionArmed = false
                                stopVoiceService()
                            }
                        }
                    }
                }
            }
        }
    }

    fun onServiceStopped() {
        endVoiceCall()
        val previousVoice = voiceTurnJob
        val previousConversation = conversationJob
        runtimeScope.launch {
            previousVoice?.join()
            previousConversation?.join()
            if (!voiceSessionArmed && ConversationWork.activeJobs.get() == 0 && modelStore.tryBeginModelOperation()) {
                try {
                    conversationEngine?.close()
                    conversationEngine = null
                    nativeConversationHasContext = false
                } finally { modelStore.endModelOperation() }
            }
        }
    }
    fun endVoiceCall(report: (String) -> Unit = sessionReport) {
        com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.cancelPending()
        if (!voiceSessionArmed && voiceTurnJob?.isActive != true) return
        // Ending a call must also release an armed microphone turn. Otherwise
        // the capture coroutine can survive the UI transition and the next
        // Voice Call cannot acquire the microphone.
        diagnosticRecorder.recordImportant("Session stop requested by UI or foreground service.")
        returnToWakeCuePending.set(false)
        pendingVoiceCorrection.set(null)
        voiceSessionArmed = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        stopVoiceService()
        runtimeScope.launch { callResources.closeMicrophone() }
        activeVoiceOutput?.stopSpeaking()
        voiceTurnJob?.cancel()
        conversationJob?.cancel()
        activeVoiceCapture = null
        runCatching {
            if (voiceSessionController.state.value != VoiceSessionState.PASSIVE_LISTENING) {
                voiceSessionController.end()
            }
        }.onFailure { report("Voice Call could not be saved: ${it.message ?: "unknown error"}") }
            .onSuccess { report("Jarvis session stopped — microphone off.") }
    }

    internal fun startVoiceDiagnostics(label: String) {
        if (label.startsWith("Voice Call ")) com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.begin(label)
        com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.clearDiagnostics()
        asrComparisonStore.clearDiagnostics()
        ttsComparisonStore.clearDiagnostics()
        diagnosticRecorder.startSession(label)
    }

    private fun stopVoiceService() {
        com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
        stopService(android.content.Intent(this, com.battlesbudz.jarvis.v2.voice.VoiceCallService::class.java))
    }

    /** Reset the native conversation without tearing down the initialized Engine. */
    internal suspend fun resetNativeConversation() {
        conversationEngine?.resetConversation()
        nativeConversationHasContext = false
        conversationCharacters = 0
    }

    internal fun cleanSpeechText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forSpeech(text)
    internal fun cleanAssistantText(text: String) = com.battlesbudz.jarvis.v2.chat.AssistantText.forDisplay(text)

    companion object {
        @Volatile private var instance: JarvisRuntime? = null
        fun get(context: android.content.Context): JarvisRuntime = instance ?: synchronized(this) {
            instance ?: JarvisRuntime(context.applicationContext).also { instance = it }
        }
    }
}
