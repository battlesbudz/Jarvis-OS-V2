package com.battlesbudz.jarvis.v2

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
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.conversation.runConversationInternal
import com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore
import com.battlesbudz.jarvis.v2.voice.AndroidAudioInput
import com.battlesbudz.jarvis.v2.voice.AsrModelStore
import com.battlesbudz.jarvis.v2.voice.VoicePreparation
import com.battlesbudz.jarvis.v2.voice.SileroSpeechDetector
import com.battlesbudz.jarvis.v2.voice.Pcm16Signal
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceTurnCoordinator
import com.battlesbudz.jarvis.v2.voice.KokoroModelStore
import com.battlesbudz.jarvis.v2.voice.SherpaKokoroVoiceOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    internal lateinit var kokoroModelStore: KokoroModelStore
    internal var conversationEngine: LiteRtLmEngine? = null
    internal var conversationJob: Job? = null
    internal var conversationCharacters = 0
    // The full transcript and rolling summary live in the app. This flag only
    // describes whether the current native Conversation has received that
    // app-managed context capsule.
    internal var nativeConversationHasContext = false
    internal val shortTermContext = ShortTermConversationContext()
    internal val referenceGrounding = ReferenceGroundingClient()
    internal val factualityVerifier = com.battlesbudz.jarvis.v2.ai.FactualityVerifier()
    internal val turnOrchestrator = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(referenceGrounding)
    internal val promptBuilder = com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder(shortTermContext)
    internal val actionIntentRouter = com.battlesbudz.jarvis.v2.actions.ActionIntentRouter()
    internal lateinit var sessionPreferences: android.content.SharedPreferences
    internal lateinit var diagnosticRecorder: com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
    internal lateinit var voiceCallStore: SharedPreferencesVoiceCallStore
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

    @Volatile internal var activeVoiceOutput: SherpaKokoroVoiceOutput? = null
    init {
        modelStore = ModelStore(applicationContext)
        kokoroModelStore = KokoroModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
        voiceCallStore = SharedPreferencesVoiceCallStore(getSharedPreferences("voice_calls", MODE_PRIVATE))
        voiceSessionController = VoiceSessionController(voiceCallStore)
        asrComparisonStore = com.battlesbudz.jarvis.v2.voice.AsrComparisonStore(getSharedPreferences("asr_comparison", MODE_PRIVATE))
        ttsComparisonStore = com.battlesbudz.jarvis.v2.voice.TtsComparisonStore(getSharedPreferences("tts_comparison", MODE_PRIVATE))
        ttsModels = com.battlesbudz.jarvis.v2.voice.TtsModelStore(applicationContext, kokoroModelStore)
        val installedPackage = packageManager.getPackageInfo(packageName, 0)
        diagnosticRecorder = com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder(sessionPreferences,
            "${installedPackage.versionName} (${installedPackage.longVersionCode})")
        diagnosticRecorder.restore()
        diagnosticRecorder.recordPreviousProcessExit(applicationContext)
        shortTermContext.restoreSummary(sessionPreferences.getString(MainActivity.SHORT_TERM_SUMMARY_KEY, null))
        runtimeScope.launch {
            for (control in com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.controls) {
                if (!voiceSessionArmed) continue
                val ui = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
                if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.RESUME) ui.paused.value = false
                else {
                    if (control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE) ui.paused.value = true
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
    @Volatile private var latestStatus = "Preparing microphone…"
    private val pendingVoiceCorrection = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn?>(null)
    private val resumeCommandCue = java.util.concurrent.atomic.AtomicBoolean(false)
    fun onMicrophoneInterruption(interrupted: Boolean, reason: String) {
        activeVoiceOutput?.setInterrupted(interrupted)
        if (interrupted) activeVoiceCapture?.yieldMicrophone()
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
        val asrEngine = com.battlesbudz.jarvis.v2.voice.MoonshineModelInfo
        val ttsEngine = ttsComparisonStore.selectedEngine()
        val asrTurnId = java.util.UUID.randomUUID().toString()
        val finalReadyAt = java.util.concurrent.atomic.AtomicLong(0)
        val speechEndedAt = java.util.concurrent.atomic.AtomicLong(0)
        val firstPlayback = java.util.concurrent.atomic.AtomicBoolean(true)
        voiceTurnJob = runtimeScope.launch(Dispatchers.Default) {
            var operationOwned = false
            var preparation: VoicePreparation? = null
            var capture: AudioTurnCapture? = null
            var microphone: AndroidAudioInput? = null
            var voiceOutput: SherpaKokoroVoiceOutput? = null
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
                check(MainActivity.activeConversationJobs.get() == 0 && modelStore.tryBeginModelOperation()) {
                    "Another model operation is still finishing. Please try again in a moment."
                }
                operationOwned = true
                voicePlayback.value = com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame()
                status("Preparing speech recognition…")
                val asrDirectory = AsrModelStore(applicationContext).ensureReady(::status)
                diagnosticRecorder.record("Voice ASR selected engine=${asrEngine.id} model=${asrEngine.modelVersion} turn=$asrTurnId")
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) { "The Gemma model failed integrity verification." }
                if (conversationEngine?.audioEnabled != true) {
                    conversationEngine?.close()
                    conversationEngine = null
                    val created = LiteRtLmEngine(
                        ModelCatalog.gemma4E2b.id, modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                        cacheDir.path, useGpu = true,
                        tools = MobileActionToolDefinitions.all(), audioEnabled = true
                    )
                    try { created.initialize() } catch (error: Throwable) { created.close(); throw error }
                    conversationEngine = created
                }
                val engine = requireNotNull(conversationEngine)
                resetNativeConversation()
                conversationCharacters = 0
                val ttsDirectory = ttsModels.ensureReady(ttsEngine, ::status)
                val input = AndroidAudioInput(this,
                    noiseSuppression = true,
                    log = { diagnosticRecorder.recordImportant("Microphone: $it") },
                    audioManager = getSystemService(android.media.AudioManager::class.java),
                    onLevel = { com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.level.value = it },
                    onWaiting = { waiting ->
                        status(if (!waiting) "Preparing microphone…" else "Paused — microphone in use by another app")
                    })
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
                    voiceSessionController.beginCall().also { startVoiceDiagnostics("Voice Call ${it.id}") }
                    diagnosticRecorder.recordImportant("Wake word detected: Hey Jarvis. ASR and call audio start now.")
                    wokeThisTurn = true
                    status("Hey Jarvis detected — getting ready to listen…")
                }
                val voiceHistory = voiceSessionController.conversationContext().map { ChatEntry(it.role, it.text) }
                val output = SherpaKokoroVoiceOutput(ttsDirectory.path, engine = ttsEngine,
                    acknowledgeDelays = true,
                    audioTrace = com.battlesbudz.jarvis.v2.voice.SpeechAudioTrace(
                        java.io.File(cacheDir, "latest-jarvis-speech.wav"), asrTurnId,
                        log = { diagnosticRecorder.recordImportant(it) }),
                    onPlayback = { voicePlayback.value = it },
                    onMetrics = { ttsComparisonStore.add(ttsEngine, "voice-call", asrTurnId, it) },
                    log = {
                        if (it.startsWith("audio_underrun") || it.startsWith("audio_supply_gap") ||
                            it.startsWith("audio_startup_buffer")) diagnosticRecorder.recordImportant("Voice TTS: $it")
                        else diagnosticRecorder.record("Voice TTS: $it")
                    })
                voiceOutput = output
                activeVoiceOutput = output
                output.setInterrupted(com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.interrupted.value)
                // Preload Kokoro while listening; this channel stays empty until final validation.
                speechJob = launch(Dispatchers.Default) {
                    try {
                        output.speak(speechChunks.receiveAsFlow()) {
                            if (firstPlayback.compareAndSet(true, false) && finalReadyAt.get() != 0L) {
                                asrComparisonStore.update(asrTurnId, "final_to_playback_start_ms",
                                    (System.nanoTime() - finalReadyAt.get()) / 1_000_000)
                                if (speechEndedAt.get() != 0L) {
                                    val elapsed = System.nanoTime() / 1_000_000 - speechEndedAt.get()
                                    asrComparisonStore.update(asrTurnId, "speech_end_to_playback_ms", elapsed)
                                    diagnosticRecorder.recordImportant("Voice latency: speech_end_to_playback_ms=$elapsed turn=$asrTurnId")
                                }
                            }
                            status("Jarvis is speaking…")
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Throwable) {
                        diagnosticRecorder.record("Voice TTS failure: ${error.message}")
                        status("Voice playback failed: ${error.message}")
                    }
                }
                val speculative = VoicePreparation(this, generate = { partial, audio, onToken ->
                    resetNativeConversation()
                    conversationCharacters = 0
                    val prompt = promptBuilder.buildGemmaPrompt(partial, null, voiceHistory, seedContext = true) + "\n" +
                        com.battlesbudz.jarvis.v2.voice.VoiceResponsePolicy.instructions
                    engine.generateAudio(prompt, audio, onToken)
                }, log = { diagnosticRecorder.record("Voice preparation: $it") },
                    prepareOpening = output::prepareOpening, speechText = ::cleanSpeechText)
                preparation = speculative
                val activeCapture = AudioTurnCapture(
                    com.battlesbudz.jarvis.v2.voice.QuietSpeechAudioInput(input, log = {
                        diagnosticRecorder.record("Voice input: $it")
                    }), this,
                    allowAudioOnlyTurns = true,
                    createDetector = { SileroSpeechDetector.create(assets) },
                    log = {
                        if (it.startsWith("asr_recovery_") || it.startsWith("empty_speech_candidate")) {
                            diagnosticRecorder.recordImportant("Voice input: $it")
                        } else diagnosticRecorder.record("Voice input: $it")
                    },
                    onRecognitionRecovery = { recovering ->
                        status(if (recovering) "Retrying speech recognition…" else "Voice Call is listening — speak now.")
                    },
                    createTranscriber = {
                        com.battlesbudz.jarvis.v2.voice.MoonshineStreamingTranscriber(asrDirectory)
                    },
                    onMetrics = { metrics, text ->
                        asrComparisonStore.add(asrTurnId, metrics, text)
                        capture?.lastSpeechAtMs?.let { speechEndedAt.set(it) }
                        diagnosticRecorder.recordImportant("Voice input summary: turn=$asrTurnId " +
                            "reason=${metrics.endpointReason} speech=${capture?.hasSpeech} chars=${text.length} " +
                            "partials=${metrics.partialUpdates} firstPartialMs=${metrics.firstPartialAfterSpeechMs} " +
                            "endpointMs=${metrics.endpointDetectionMs}")
                    },
                    onSpeechResumed = speculative::speechResumed,
                    onPartialTranscript = { text, audio ->
                        speculative.submit(text, audio)
                        mainHandler.post {
                            if (activeVoiceCapture === capture) onTranscript("You", text, false)
                        }
                    }
                )
                capture = activeCapture
                activeVoiceCapture = activeCapture
                val correction = pendingVoiceCorrection.getAndSet(null)
                if (correction == null) activeCapture.start()
                voiceSessionController.setState(VoiceSessionState.ACTIVELY_LISTENING)
                status("Voice Call is listening — speak now.")
                if (correction == null && (wokeThisTurn || resumeCommandCue.getAndSet(false))) {
                    com.battlesbudz.jarvis.v2.voice.VoiceCues.play(
                        com.battlesbudz.jarvis.v2.voice.VoiceCues.Cue.COMMAND_READY,
                        log = { diagnosticRecorder.recordImportant(it) })
                    diagnosticRecorder.recordImportant("Wake acknowledged; command microphone ready.")
                }
                if (correction == null) activeCapture.awaitTurnCompletion()
                val endpointAt = System.nanoTime()
                finalReadyAt.set(endpointAt)
                val firstFinalToken = java.util.concurrent.atomic.AtomicBoolean(true)
                val audioBytes = correction?.wav ?: activeCapture.stop()
                val asrTranscript = correction?.transcript ?: activeCapture.finalTranscript
                if (activeVoiceCapture === activeCapture) activeVoiceCapture = null
                status("Processing your Voice Call turn locally…")
                if (correction == null && !activeCapture.hasSpeech) {
                    diagnosticRecorder.record("Voice call ended reason=inactivity timeoutMs=20000")
                    voiceSessionController.end()
                    finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " no recognized speech for 20 seconds."
                    return@launch
                }
                val draft = speculative.seal(asrTranscript)
                val transcript = com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.resolve(
                    asrTranscript, audioBytes
                ) { audio ->
                    status("Listening to your recorded speech with Gemma…")
                    diagnosticRecorder.recordImportant("Voice audio fallback: Moonshine empty; Gemma receiving ${audio.size} bytes")
                    resetNativeConversation()
                    try {
                        val heard = engine.generateAudio(
                            com.battlesbudz.jarvis.v2.voice.VoiceTranscriptResolver.instructions, audio, {})
                        // This recognition pass never dispatches tools or speaks model output.
                        if (heard.toolCalls.isEmpty()) heard.text else ""
                    } finally { resetNativeConversation() }
                }
                if (asrTranscript.isBlank()) {
                    diagnosticRecorder.recordImportant("Voice audio fallback finished: chars=${transcript.length} source=gemma")
                }
                if (com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(transcript)) {
                    speculative.close()
                    voiceSessionController.appendTranscript("You", transcript)
                    voiceSessionController.end()
                    mainHandler.post { onTranscript("You", transcript, true) }
                    diagnosticRecorder.record("Voice call ended reason=spoken_goodbye")
                    finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " goodbye."
                    return@launch
                }
                asrComparisonStore.update(asrTurnId, "prepared", draft != null)
                if (draft == null) resetNativeConversation()
                diagnosticRecorder.record("Voice ASR final\ntext=$transcript\naudioBytes=${audioBytes.size}\nprepared=${draft != null}")
                mainHandler.post { onTranscript("You", transcript, true) }
                output.acknowledgeConfirmedTurn()
                val outcome = com.battlesbudz.jarvis.v2.voice.runInterruptibleReply(
                    reply = {
                        val coordinator = VoiceTurnCoordinator(voiceSessionController)
                        val response = coordinator.processTurn(transcript) { onToken ->
                            val completed = CompletableDeferred<String>()
                            val streamed = StringBuilder()
                            fun recordFirstText(text: String) {
                                if (text.isNotBlank() && firstFinalToken.compareAndSet(true, false)) {
                                    val elapsedMs = (System.nanoTime() - endpointAt) / 1_000_000
                                    asrComparisonStore.update(asrTurnId, "final_to_first_text_ms", elapsedMs)
                                    if (speechEndedAt.get() != 0L) {
                                        asrComparisonStore.update(asrTurnId, "speech_end_to_first_text_ms",
                                            System.nanoTime() / 1_000_000 - speechEndedAt.get())
                                    }
                                    diagnosticRecorder.record("Voice latency: endpoint_to_first_text_ms=$elapsedMs")
                                }
                            }
                            runConversationInternal(
                                prompt = transcript, history = voiceHistory, imageUri = null,
                                preparedVoice = draft, voiceAudio = audioBytes,
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
                            conversationJob?.join()
                            mainHandler.post { onTranscript("Jarvis", text, true) }
                            com.battlesbudz.jarvis.v2.ai.GenerationResult(text, -1L, null)
                        }
                        val last = voiceSessionController.currentTranscript().lastOrNull()
                        if (last?.role != "Jarvis" || last.text != response.text) {
                            voiceSessionController.appendTranscript("Jarvis", response.text, complete = true)
                        }
                        speechChunks.close()
                        speechJob?.join()
                        response
                    },
                    listen = { confirmed ->
                        com.battlesbudz.jarvis.v2.voice.ReplyVoiceCapture(applicationContext) {
                            diagnosticRecorder.recordImportant("Voice interruption: $it")
                        }.listen(output, asrDirectory, confirmed)
                    },
                    stopReply = {
                        output.stopSpeaking()
                        speechJob?.cancel()
                        conversationJob?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(
                            com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY))
                        diagnosticRecorder.recordImportant("Voice reply interrupted by speech; call retained, action not replayed.")
                        status("Voice Call is listening — speak now.")
                    }
                )
                if (outcome is com.battlesbudz.jarvis.v2.voice.ReplyOutcome.Interrupted) {
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
                    if (cancelled.control != com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY) {
                        runCatching { voiceSessionController.end() }
                    }
                    finalMessage = com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " user control."
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
                    } finally {
                        speechChunks.close()
                        runCatching { voiceOutput?.stopSpeaking() }
                        speechJob?.cancel()
                        speechJob?.join()
                        runCatching { voiceOutput?.release() }
                        if (activeVoiceOutput === voiceOutput) activeVoiceOutput = null
                        if (activeVoiceCapture === capture) activeVoiceCapture = null
                        if (operationOwned) modelStore.endModelOperation()
                    }
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
            if (!voiceSessionArmed && MainActivity.activeConversationJobs.get() == 0 && modelStore.tryBeginModelOperation()) {
                try {
                    conversationEngine?.close()
                    conversationEngine = null
                    nativeConversationHasContext = false
                } finally { modelStore.endModelOperation() }
            }
        }
    }
    fun endVoiceCall(report: (String) -> Unit = sessionReport) {
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
        com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.clearDiagnostics()
        asrComparisonStore.clearDiagnostics()
        ttsComparisonStore.clearDiagnostics()
        diagnosticRecorder.startSession(label)
    }

    private fun stopVoiceService() {
        stopService(android.content.Intent(this, com.battlesbudz.jarvis.v2.voice.VoiceCallService::class.java))
    }

    /** Reset the native conversation without tearing down the initialized Engine. */
    internal suspend fun resetNativeConversation() {
        conversationEngine?.resetConversation()
        nativeConversationHasContext = false
        conversationCharacters = 0
    }

    internal fun cleanSpeechText(text: String): String = text
        .replace("*", "")
        .replace("_", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s*#+\\s*"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")

    internal fun cleanAssistantText(text: String): String {
        val cleaned = text
            .replace(
                Regex("""(?s)(?:<\|)?tool_call>.*?(?:<\|tool_call\|>|$)"""),
                ""
            )
            .replace(
                Regex("""(?s)<start_function_call>.*?(?:<end_function_call>|$)"""),
                ""
            )
            .replace(Regex("""(?i)<\|tool_call\|>|<end_function_call>|<\|end_function_call\|>"""), "")
            .trim()
        return cleaned
    }

    companion object {
        @Volatile private var instance: JarvisRuntime? = null
        fun get(context: android.content.Context): JarvisRuntime = instance ?: synchronized(this) {
            instance ?: JarvisRuntime(context.applicationContext).also { instance = it }
        }
    }
}
