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
import com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue
import com.battlesbudz.jarvis.v2.actions.AcceptedActionLease
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.conversation.runConversationInternal
import com.battlesbudz.jarvis.v2.memory.AndroidMemoryOs
import com.battlesbudz.jarvis.v2.memory.ConversationMemory
import com.battlesbudz.jarvis.v2.memory.ConversationMemorySource
import com.battlesbudz.jarvis.v2.memory.FinalMemoryInput
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frozen final ASR evidence; this is scheduled work, not a second action executor. */
private data class AcceptedVoiceInvocation(
    val callId: String,
    val replyId: String,
    val utteranceId: String,
    val prompt: String,
    val history: List<ChatEntry>,
    val plan: ActionTurnPlan.Ready
)

/** Application-context runtime. The foreground service owns voice execution; UI only observes. */
internal class JarvisRuntime private constructor(context: android.content.Context) : android.content.ContextWrapper(context) {
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // This worker outlives a single TTS delivery/capture attempt. It is closed only with the
    // process runtime; end-call detaches audio but retains bounded accepted Android work.
    private val acceptedVoiceActions = AcceptedActionQueue<AcceptedVoiceInvocation>(scope = runtimeScope)
    @Volatile private var activeContinuousActionSession: com.battlesbudz.jarvis.v2.voice.ContinuousActionSession<AcceptedVoiceInvocation>? = null
    /** Transferred from the initial final-ASR turn and retained through the entire FIFO batch. */
    private val acceptedVoiceLease = AcceptedActionLease()
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
    private val memoryEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val memoryBoundaryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val memoryDeliveryFence = MemoryDeliveryFence()
    private val memoryHistoryCutoff = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var nativeMemoryStateToken: String? = null
    private val conversationMemory by lazy { ConversationMemory(AndroidMemoryOs.get(applicationContext)) }
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
        nativeMemoryStateToken = sessionPreferences.getString("approved_memory_context_token", null)
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
        AndroidMemoryOs.get(applicationContext).addApprovedStateObserver {
            // Fence output immediately, then durably publish the context boundary off the caller
            // thread. Advancing the epoch last prevents a fresh packet being paired with history
            // that still contains an erased/corrected fact.
            memoryBoundaryPending.set(true)
            memoryDeliveryFence.invalidate()
            memoryVoiceBinding.getAndSet(null)?.let { binding ->
                binding.speechJob?.cancel()
                binding.expiryJob.getAndSet(null)?.cancel()
                runtimeScope.launch { binding.output.stopSpeaking() }
            }
            // Production mutation calls are already owned IO. If a future caller invokes the
            // observer on main, fail closed until this owned IO boundary is durable.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runtimeScope.launch(Dispatchers.IO) { publishMemoryContextBoundary() }
            } else publishMemoryContextBoundary()
        }
        // Persist terminal reply evidence even after End Call detached its audio session. Waiting
        // for queue idle means active cancellation has joined its exact native child first.
        runtimeScope.launch {
            acceptedVoiceActions.events.collect { event ->
                if (event.task.state in setOf(com.battlesbudz.jarvis.v2.actions.AcceptedActionState.COMPLETED,
                        com.battlesbudz.jarvis.v2.actions.AcceptedActionState.FAILED,
                        com.battlesbudz.jarvis.v2.actions.AcceptedActionState.CANCELLED,
                        com.battlesbudz.jarvis.v2.actions.AcceptedActionState.INTERRUPTED)) {
                    runtimeScope.launch {
                        acceptedVoiceActions.awaitIdle()
                        acceptedVoiceActions.tasks.value.forEach { terminal ->
                            if (terminal.state in setOf(com.battlesbudz.jarvis.v2.actions.AcceptedActionState.COMPLETED,
                                    com.battlesbudz.jarvis.v2.actions.AcceptedActionState.FAILED,
                                    com.battlesbudz.jarvis.v2.actions.AcceptedActionState.CANCELLED,
                                    com.battlesbudz.jarvis.v2.actions.AcceptedActionState.INTERRUPTED)) {
                                persistTerminalActionReply(terminal)
                            }
                        }
                    }
                }
            }
        }
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
                    val acceptedMode = acceptedVoiceActions.hasUnfinished() ||
                        (activeContinuousActionSession?.pendingReportCount() ?: 0) > 0
                    if (acceptedMode && control == com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY) {
                        activeContinuousActionSession?.interruptDelivery()
                        diagnosticRecorder.recordImportant("UI STOP_REPLY detached accepted delivery; ASR/action worker retained")
                    } else if (acceptedMode && control == com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE) {
                        // The pump remains the call owner and waits for Resume; it must not
                        // reenter ordinary runVoiceTurn while its accepted batch is active.
                        diagnosticRecorder.recordImportant("UI PAUSE suspended accepted capture; worker/report obligations retained")
                    } else voiceTurnJob?.cancel(com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation(control))
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
        if (voiceSessionArmed) {
            if (attachment != null) return "Attachments are unavailable while a Voice Call is active."
            val callId = voiceSessionController.currentCallId() ?: return "Voice Call is waiting for its wake word; keep this draft until the call is listening."
            val queued = com.battlesbudz.jarvis.v2.voice.CallFinalInput(java.util.UUID.randomUUID().toString(), callId,
                conversationHistory.current.value.id, userText, System.currentTimeMillis())
            val priorityControl = com.battlesbudz.jarvis.v2.voice.VoiceActionControl.parse(userText, hasUnfinished = true) !=
                com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None
            return when (callInputQueue.offer(queued, callId, priorityControl)) {
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Queued -> { runVoiceTurn(); null }
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Full -> "Voice Call input queue is full. Wait for the current turn."
                com.battlesbudz.jarvis.v2.voice.CallInputAdmission.Ended -> "Voice Call ended before that message was accepted."
            }
        }
        if (chatBusy.value || voiceTurnJob?.isCompleted == false ||
            conversationJob?.isCompleted == false || acceptedVoiceActions.hasUnfinished() ||
                ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
            return "Wait for the current response or voice session to finish."
        if (!modelStore.isUsable() || !modelStore.smokeTestPassed()) return "Set up and test a model first."
        val history = conversationHistory.context()
        val threadId = conversationHistory.current.value.id
        val replyId = java.util.UUID.randomUUID().toString()
        val userMessageId = conversationHistory.appendUser(userText, attachment)
        val capturedAtMs = System.currentTimeMillis()
        runtimeScope.launch(Dispatchers.IO) {
            if (!captureFinalMemory(userMessageId, threadId, null, ConversationMemorySource.TEXT, userText, capturedAtMs)) {
                mainHandler.post { sessionReport("Memory proposal could not be saved; the conversation was sent normally.") }
            }
        }
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
                    },
                    onActionResult = { name, message, succeeded ->
                        conversationHistory.recordReplyAction(threadId, replyId,
                            com.battlesbudz.jarvis.v2.chat.ActionReceipt(name, message, succeeded))
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
            acceptedVoiceActions.hasUnfinished() || ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())
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
    private val callInputQueue = com.battlesbudz.jarvis.v2.voice.CallInputQueue()
    private val typedTurnOwner = com.battlesbudz.jarvis.v2.voice.CallTurnOwner()
    /** One popped input remains owned across a transient lease race even if FIFO refills. */
    private val pendingTypedHandoff = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.CallFinalInput?>(null)
    /** Owns a polled final input until it is appended/promoted or visibly terminalized. */
    private val preparingTypedInput = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.CallFinalInput?>(null)
    private data class MemoryVoiceBinding(
        val ticket: MemoryDeliveryFence.Ticket,
        val context: MemoryTurnContext,
        val output: PiperVoiceOutput,
        val speechJob: Job?,
        val callId: String,
        val replyId: String,
        val expiryJob: java.util.concurrent.atomic.AtomicReference<Job?>
    )
    private val memoryVoiceBinding = java.util.concurrent.atomic.AtomicReference<MemoryVoiceBinding?>(null)
    private val resumeCommandCue = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun publishMemoryContextBoundary() {
        try {
            if (!conversationHistory.markMemoryContextCutoff(durable = true)) error("memory_context_cutoff_persist_failed")
            shortTermContext.clear()
            if (!sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).commit()) error("memory_summary_clear_failed")
            memoryHistoryCutoff.set(true)
            // Last: no fresh turn may pair the new approved token with pre-mutation context.
            memoryEpoch.incrementAndGet()
            memoryBoundaryPending.set(false)
        } catch (failure: Throwable) {
            // Leave the barrier closed: storage recovery must never reopen stale resident context.
            diagnosticRecorder.recordImportant("Memory context boundary persistence failed: ${failure.javaClass.simpleName}")
        }
    }

    internal fun freshMemoryTurnContext(query: String, maxChars: Int): MemoryTurnContext? {
        if (memoryBoundaryPending.get()) return null
        // An approved mutation can happen between a store read and context construction. Retry
        // once rather than labeling that older packet with the newer epoch.
        repeat(2) {
            val before = memoryEpoch.get()
            val result = conversationMemory.approvedContext(query, maxChars, limit = 8)
            if (memoryBoundaryPending.get() || before != memoryEpoch.get()) return@repeat
            val packet = result.packet ?: return null
            // Core supplies the earliest approved expiry from this same coherent snapshot,
            // including facts absent from this packet but retained by history/native context.
            return MemoryTurnContext(packet.text, result.stateToken, query, result.nextApprovedExpiryMs, before,
                hasApprovedMemories = packet.memories.isNotEmpty()) {
                if (memoryBoundaryPending.get()) Long.MIN_VALUE else memoryEpoch.get()
            }
        }
        return null
    }

    internal fun adoptMemoryState(context: MemoryTurnContext): Boolean {
        // Persist only the opaque snapshot token, never memory text, so a process restart still
        // recognizes expiry/correction before it can reseed saved dialogue.
        val previous = nativeMemoryStateToken ?: sessionPreferences.getString("approved_memory_context_token", null)
        val changed = com.battlesbudz.jarvis.v2.memory.MemoryContextPersistence.adopt(
            previousToken = previous,
            newToken = context.stateToken,
            persistCutoff = {
                memoryHistoryCutoff.set(true)
                conversationHistory.markMemoryContextCutoff(durable = true)
            },
            clearSummary = {
                shortTermContext.clear()
                sessionPreferences.edit().remove(ConversationPolicy.SHORT_TERM_SUMMARY_KEY).commit()
            },
            persistToken = { token ->
                val tokenEdit = sessionPreferences.edit()
                if (token == null) tokenEdit.remove("approved_memory_context_token")
                else tokenEdit.putString("approved_memory_context_token", token)
                tokenEdit.commit()
            }
        )
        nativeMemoryStateToken = context.stateToken
        return changed
    }

    internal fun isMemoryTurnCurrent(context: MemoryTurnContext): Boolean {
        if (!context.isCurrent()) return false
        // Expiry changes the approved token without a mutation observer, so recheck the one-read
        // state token at the user-visible completion boundary.
        return conversationMemory.approvedContext(context.query, 0, limit = 8).stateToken == context.stateToken
    }

    internal fun consumeMemoryHistoryCutoff(): Boolean = memoryHistoryCutoff.getAndSet(false)

    private fun captureFinalMemory(eventId: String, conversationId: String, callId: String?,
                                   source: ConversationMemorySource, text: String, capturedAtMs: Long): Boolean {
        val result = conversationMemory.capture(FinalMemoryInput(eventId, conversationId, callId, source, text, capturedAtMs))
        val stored = result.outcome != com.battlesbudz.jarvis.v2.memory.ConversationMemoryOutcome.STORAGE_FAILURE
        if (!stored) diagnosticRecorder.recordImportant("Memory capture storage failure for finalized $source input; conversation continues.")
        return stored
    }

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
    private fun runTypedCallInput(input: com.battlesbudz.jarvis.v2.voice.CallFinalInput) {
        if (!typedTurnOwner.begin(input.id)) return
        // Every admitted input either completes or is restored and wakes its exact owner again.
        // This must be set before a lease check so a transient external model operation cannot
        // silently strand the FIFO entry.
        val restartAfterCompletion = java.util.concurrent.atomic.AtomicBoolean(true)
        voiceTurnJob = runtimeScope.launch(Dispatchers.Default) {
            var operationOwned = false
            var invocation: Job? = null
            try {
                if (!voiceSessionArmed || voiceSessionController.currentCallId() != input.callId) return@launch
                if (ConversationWork.activeJobs.get() != 0 || !modelStore.tryBeginModelOperation()) {
                    if (!callInputQueue.restore(input, voiceSessionController.currentCallId())) {
                        check(pendingTypedHandoff.compareAndSet(null, input)) { "typed_call_handoff_slot_already_owned" }
                    }
                    mainHandler.post { sessionReport("Your typed Voice Call message is still queued until the current turn finishes.") }
                    return@launch
                }
                operationOwned = true
                val replyId = "typed-" + input.id
                captureFinalMemory(input.id, input.conversationId, input.callId, ConversationMemorySource.TEXT, input.text, input.capturedAtMs)
                val response = StringBuilder()
                if (!callInputQueue.promote(input) {
                        preparingTypedInput.compareAndSet(input, null)
                        voiceSessionController.appendTranscript("You", input.text, origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                        voiceSessionController.beginReply(input.callId, replyId)
                        invocation = runConversationInternal(input.text, conversationHistory.context(), null,
                            callOwned = true,
                            onToken = { token -> response.append(token); voiceSessionController.updateReplyText(input.callId, replyId, response.toString()) },
                            onComplete = { answer -> voiceSessionController.updateReplyText(input.callId, replyId, answer, finished = true) })
                    }) return@launch
                invocation?.join()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Pause/microphone cancellation retains an unpromoted typed final for this call;
                // End has already drained it and wins the visible terminal receipt.
                if (callInputQueue.restore(input, voiceSessionController.currentCallId())) restartAfterCompletion.set(true)
                else if (callInputQueue.terminalize(input)) {
                    voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                        "Cancelled before processing typed message: ${input.text}")
                }
                throw cancelled
            } catch (error: Throwable) {
                diagnosticRecorder.recordImportant("Typed Voice Call turn failed: ${error.javaClass.simpleName}")
                // If End already won, its queue drain owns the one visible receipt. Otherwise
                // this exact claimed input becomes terminal here and can never be replayed.
                val terminalized = callInputQueue.terminalize(input)
                mainHandler.post {
                    if (terminalized) voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                        "Cancelled before processing typed message: ${input.text}")
                    voiceSessionController.updateReplyText(input.callId, "typed-" + input.id,
                        "The typed Voice Call message was interrupted before completion.", finished = true)
                    sessionReport("Typed Voice Call message interrupted; it was not replayed.")
                }
            } finally {
                typedTurnOwner.finish(input.id, invocation) {
                    if (operationOwned) modelStore.endModelOperation()
                }
            }
        }.also { ownerJob ->
            ownerJob.invokeOnCompletion {
                typedTurnOwner.restartAfterOwnerCompletion(input.id) {
                    if (restartAfterCompletion.get()) runtimeScope.launch {
                        // Do not rely on a second user tap: wait for the prior exclusive owner to
                        // leave, then invoke only after this outer typed owner has completed.
                        while (voiceSessionArmed && voiceSessionController.currentCallId() == input.callId &&
                            (ConversationWork.activeJobs.get() != 0 || modelStore.isModelOperationActive())) {
                            kotlinx.coroutines.delay(50)
                        }
                        mainHandler.post {
                            if (voiceSessionArmed && voiceSessionController.currentCallId() == input.callId) runVoiceTurn()
                        }
                    }
                }
            }
        }
    }
    fun runVoiceTurn() {
        if (!voiceSessionArmed || voiceTurnJob?.isCompleted == false || acceptedVoiceActions.hasUnfinished() ||
            (activeContinuousActionSession?.pendingReportCount() ?: 0) > 0) return
        // A final typed phone command gets the exact same strict plan/lease/accepted queue as a
        // final spoken command. Ordinary typed turns keep the dedicated CallTurnOwner path.
        val restoredTypedInput = pendingTypedHandoff.getAndSet(null)
        val nextTypedInput = restoredTypedInput ?: voiceSessionController.currentCallId()?.let { callInputQueue.claim(it) }
        if (restoredTypedInput != null && !callInputQueue.claimExternal(restoredTypedInput)) return
        val queuedTypedInput = nextTypedInput?.also { typed ->
            preparingTypedInput.set(typed)
            val plan = turnOrchestrator.plan(typed.text, conversationHistory.contextAfterMemoryCutoff().map { it.role to it.text }).actionPlan
            if (plan !is ActionTurnPlan.Ready) {
                runTypedCallInput(typed)
                return
            }
        }
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
            val promotionLease = com.battlesbudz.jarvis.v2.voice.CallPromotionLease()
            var preparation: com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput? = null
            var capture: AudioTurnCapture? = null
            var microphone: com.battlesbudz.jarvis.v2.voice.AudioInput? = null
            var expectedResourceCall: String? = null
            var preserveCaptureOnCancellation = false
            var voiceOutput: PiperVoiceOutput? = null
            val finalSpeechDelivery = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.SpeechDelivery?>(null)
            val speechChunks = Channel<String>(Channel.UNLIMITED)
            var speechJob: Job? = null
            // This turn-local reference survives global detachment so every actual publication
            // can distinguish an unbound safe error from an invalidated memory answer.
            val answerMemoryBinding = java.util.concurrent.atomic.AtomicReference<MemoryVoiceBinding?>(null)
            val answerExpiryJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
            // Every pump-polled typed input remains here until atomic promotion or terminal receipt.
            val activePumpTypedInput = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.CallFinalInput?>(null)
            var microphoneYielded = false
            val hadActiveCall = voiceSessionController.currentCallId() != null
            var wokeThisTurn = false
            var finalMessage = "Voice Call turn failed."
            fun relinquishUnpromotedTypedInput() {
                val typed = queuedTypedInput ?: return
                if (callInputQueue.restore(typed, voiceSessionController.currentCallId())) {
                    preparingTypedInput.compareAndSet(typed, null)
                    return
                }
                if (callInputQueue.terminalize(typed)) {
                    preparingTypedInput.compareAndSet(typed, null)
                    voiceSessionController.recordTerminalInputForCall(typed.callId, typed.id,
                        "Cancelled before processing typed message: ${typed.text}")
                }
            }
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
                        output.speak(speechChunks.receiveAsFlow().filter {
                            val binding = answerMemoryBinding.get()
                            binding == null ||
                                (memoryVoiceBinding.get() === binding && memoryDeliveryFence.isValid(binding.ticket) && binding.context.isCurrent())
                        }) {
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
                val correction = queuedTypedInput?.let { typed ->
                    com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                        typed.text, byteArrayOf(), utteranceId = typed.id, capturedAtMs = typed.capturedAtMs,
                        origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
                    )
                } ?: pendingVoiceCorrection.getAndSet(null)
                comparison?.put("input_is_interruption_correction", correction != null)
                comparison?.mark("capture_start")
                if (correction == null) activeCapture.start(com.battlesbudz.jarvis.v2.voice.CallLifetimePolicy.initialSilenceTimeoutMs())
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
                if (correction == null) {
                    // The ASR owner retains a begun utterance. A typed draft can hand off the
                    // microphone only before speech starts; otherwise it stays FIFO behind that
                    // immutable final input, so neither source is discarded or double-run.
                    val captureFinished = async { activeCapture.awaitTurnCompletion() }
                    val typedAvailable = async { callInputQueue.awaitAvailable(expectedCallId) }
                    val typedWon = kotlinx.coroutines.selects.select<Boolean> {
                        captureFinished.onAwait { false }
                        typedAvailable.onAwait { true }
                    }
                    if (typedWon && !activeCapture.hasSpeech) {
                        activeCapture.stop()
                        captureFinished.cancelAndJoin()
                        finalMessage = "Typed Voice Call input accepted; processing it now."
                        return@launch
                    }
                    typedAvailable.cancelAndJoin()
                    if (!captureFinished.isCompleted) captureFinished.await()
                }
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
                    diagnosticRecorder.record("Voice call inactivity: capture window completed; armed session retained")
                    finalMessage = com.battlesbudz.jarvis.v2.voice.CallLifetimePolicy.waitingStatus()
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
                if (recognitionIssue == null && transcript.isNotBlank()) {
                    captureFinalMemory(correction?.utteranceId ?: asrTurnId, conversationHistory.current.value.id,
                        expectedCallId, if (correction?.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                            ConversationMemorySource.TEXT else ConversationMemorySource.VOICE,
                        transcript, correction?.capturedAtMs ?: System.currentTimeMillis())
                }
                if (recognitionIssue == null && com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(transcript)) {
                    incremental.close()
                    voiceSessionController.appendTranscript("You", transcript)
                    endVoiceCall()
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
                // Action mode is entered only after final ASR and the shared strict plan. It keeps
                // native work on acceptedVoiceActions while this turn owns an ASR-only follow-up pump.
                val initialActionPlan = if (recognitionIssue == null)
                    turnOrchestrator.plan(transcript, voiceHistory.map { it.role to it.text }).actionPlan
                    else ActionTurnPlan.NotAction
                suspend fun runAcceptedActionMode(): Boolean {
                    if (comparison != null || initialActionPlan !is ActionTurnPlan.Ready) return false
                    incremental.close() // Never leave speculative prefill attached to a queued native turn.
                    // A typed input can be End-drained while preparation is in progress. Keep
                    // the ordinary lease until its successful atomic promotion, then transfer it
                    // together with append/begin/enqueue so a rejected promotion cannot strand it.
                    fun acquireAcceptedLease(): Boolean = promotionLease.transfer {
                        if (operationOwned) {
                            transferAcceptedVoiceLease()
                            operationOwned = false
                            true
                        } else retainAcceptedVoiceLease() != null
                    }
                    val actionSession = com.battlesbudz.jarvis.v2.voice.ContinuousActionSession(acceptedVoiceActions)
                    val actionInvocation = AcceptedVoiceInvocation(
                        expectedCallId, asrTurnId, correction?.utteranceId ?: asrTurnId, transcript, voiceHistory, initialActionPlan
                    )
                    var accepted = false
                    var leaseRejected = false
                    fun admitAction() {
                        if (!acquireAcceptedLease()) {
                            leaseRejected = true
                            return
                        }
                        voiceSessionController.appendTranscript("You", transcript,
                            origin = correction?.origin ?: com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.SPOKEN)
                        voiceSessionController.beginReply(expectedCallId, asrTurnId)
                        voiceSessionController.setState(VoiceSessionState.EXECUTING_ACTION)
                        activeContinuousActionSession = actionSession
                        accepted = enqueueAcceptedVoiceAction(actionSession, actionInvocation)
                    }
                    if (queuedTypedInput != null) {
                        val typed = queuedTypedInput
                        if (!callInputQueue.promote(typed) {
                                preparingTypedInput.compareAndSet(typed, null)
                                admitAction()
                            }) return true
                    } else admitAction()
                    if (!accepted) {
                        promotionLease.releaseIfUnadmitted(::releaseAcceptedVoiceLeaseIfIdle)
                        val rejection = if (leaseRejected) "The local model is still busy. Please repeat that phone request shortly."
                            else "I already have three accepted phone requests. Please wait for one to finish."
                        voiceSessionController.updateReplyText(expectedCallId, asrTurnId, rejection, finished = true)
                        finalMessage = rejection
                        return true
                    }
                    promotionLease.markAdmitted() // accepted queue now owns release through its idle lifecycle.
                    diagnosticRecorder.recordImportant("Accepted action mode: admitted=$asrTurnId steps=${initialActionPlan.steps.size} call=$expectedCallId")
                    // The action worker is already running while this listener waits. Do not call
                    // runVoiceTurn here: that would reset/prefill the shared native engine.
                    var terminalReportsPublished = false
                    // A fast executor can finish before the listener exists. Reconcile its durable
                    // terminal record before deciding whether an idle watcher is needed.
                    publishTerminalActionReports(actionSession, expectedCallId)
                    // Stay false until this pump observes/reconciles one idle transition; a
                    // completion between the scan and this point still wakes workerIdle.
                    // Shared with the persistent ASR listener so it always interrupts the current
                    // report output, not a stale per-loop capture.
                    var reportOutput: PiperVoiceOutput? = null
                    var activeFollowup: kotlinx.coroutines.Deferred<com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn>? = null
                    actionPump@ while ((acceptedVoiceActions.hasUnfinished() || actionSession.pendingReportCount() > 0 ||
                        !terminalReportsPublished || actionSession.isCaptureInProgress()) &&
                        voiceSessionArmed && voiceSessionController.currentCallId() == expectedCallId) {
                        if (com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value) {
                            // Pausing parks microphone/TTS only. A retained typed scoped control
                            // still reaches the durable accepted queue while a task is blocked.
                            val pausedControl = callInputQueue.claim(expectedCallId) { candidate ->
                                actionSession.control(candidate.text, acceptedVoiceActions.hasUnfinished()) !=
                                    com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None
                            }
                            if (pausedControl != null) {
                                activePumpTypedInput.set(pausedControl)
                                val control = actionSession.control(pausedControl.text, acceptedVoiceActions.hasUnfinished())
                                try {
                                    captureFinalMemory(pausedControl.id, pausedControl.conversationId, expectedCallId,
                                        ConversationMemorySource.TEXT, pausedControl.text, pausedControl.capturedAtMs)
                                } catch (failure: Throwable) {
                                    if (callInputQueue.terminalize(pausedControl)) {
                                        activePumpTypedInput.compareAndSet(pausedControl, null)
                                        voiceSessionController.recordTerminalInputForCall(pausedControl.callId, pausedControl.id,
                                            "Cancelled before processing typed message: ${pausedControl.text}")
                                    }
                                    diagnosticRecorder.recordImportant("Paused typed control capture failed: ${failure.javaClass.simpleName}")
                                    continue@actionPump
                                }
                                var stopListening = false
                                if (!callInputQueue.promote(pausedControl) {
                                        voiceSessionController.appendTranscript("You", pausedControl.text,
                                            origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                                        if (control == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly &&
                                            pausedControl.text.trim().lowercase().trimEnd('.', '!', '?') == "stop listening") {
                                            stopListening = true
                                        } else if (control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly) {
                                            acceptedVoiceActions.cancel(control) { it.value.callId == expectedCallId }
                                        }
                                    }) continue@actionPump
                                activePumpTypedInput.compareAndSet(pausedControl, null)
                                if (stopListening) {
                                    endVoiceCall()
                                    break@actionPump
                                }
                                continue@actionPump
                            }
                            delay(100)
                            continue@actionPump
                        }
                        if (!voiceSessionArmed) break@actionPump
                        // Keep a single ASR capture alive while terminal reports become ready and
                        // while Piper swaps to a fresh delivery ledger. A barge-in therefore stops
                        // only the current report attempt; it never discards the final utterance.
                        val followup = activeFollowup ?: async {
                            com.battlesbudz.jarvis.v2.voice.ReplyVoiceCapture(applicationContext) {
                                diagnosticRecorder.recordImportant("Action follow-up capture: $it")
                            }.listen(output, asrDirectory, onConfirmed = {
                                actionSession.onCaptureStarted()
                            }, asrEngine = asrEngine,
                                inputFactory = { callResources.borrowMicrophone("accepted-followup", communication = true) },
                                modelSession = models, asrOnly = true,
                                outputProvider = { reportOutput ?: output })
                        }.also { activeFollowup = it }
                        val deliveryReady = async { actionSession.awaitDeliveryReady() }
                        // Queue cancellation can terminally skip a task without entering its
                        // executor callback. Its durable task record still needs a report, so
                        // wake on actual worker-idle as well as a pre-existing pending report.
                        val workerIdle = if (actionSession.needsIdleObservation(terminalReportsPublished)) async { acceptedVoiceActions.awaitIdle() } else null
                        // Typed finals enter the same accepted-call owner. They wake a silent ASR
                        // listener without pretending to be WAV; a begun spoken floor wins first.
                        fun typedDispatchable(candidate: com.battlesbudz.jarvis.v2.voice.CallFinalInput): Boolean {
                            val control = actionSession.control(candidate.text, acceptedVoiceActions.hasUnfinished())
                            if (control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None) return true
                            return when (turnOrchestrator.plan(candidate.text, voiceHistory.map { it.role to it.text }).actionPlan) {
                                is ActionTurnPlan.Ready, is ActionTurnPlan.Rejected -> true
                                else -> !actionSession.hasDeferredConversation() && !actionSession.isCaptureInProgress()
                            }
                        }
                        val typedAvailable = async { callInputQueue.awaitDispatchable(expectedCallId, ::typedDispatchable) }
                        var typedInput: com.battlesbudz.jarvis.v2.voice.CallFinalInput? = null
                        var captured: com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn? = null
                        val pumpEvent = com.battlesbudz.jarvis.v2.voice.awaitActionPumpEvent(
                            followup, deliveryReady, workerIdle, typedAvailable
                        ) { capturedTurn ->
                            captured = capturedTurn
                            activeFollowup = null
                        }
                        if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.TYPED_AVAILABLE) {
                            typedInput = callInputQueue.claim(expectedCallId, ::typedDispatchable)
                            // Keep a begun ASR capture alive. The next loop reuses this exact
                            // deferred listener while the typed control/action is admitted.
                            // Never cancel this persistent listener on a typed handoff. Speech
                            // can confirm between a stale state read and cancellation; retaining
                            // its exact deferred preserves the next immutable spoken final.
                            typedInput?.let { typed ->
                                activePumpTypedInput.set(typed)
                                captured = com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                                    typed.text, byteArrayOf(), utteranceId = typed.id,
                                    capturedAtMs = typed.capturedAtMs,
                                    origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
                                )
                            }
                        }
                        if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.DELIVERY_READY ||
                            pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.WORKER_IDLE) {
                            publishTerminalActionReports(actionSession, expectedCallId)
                            terminalReportsPublished = true
                            val report = actionSession.nextDelivery()
                            if (report != null) {
                                speechChunks.close()
                                withContext(kotlinx.coroutines.NonCancellable) { speechJob?.cancelAndJoin() }
                                val reportReplyId = "report-" + java.util.UUID.randomUUID().toString()
                                voiceSessionController.beginReply(expectedCallId, reportReplyId)
                                val reportTerminal = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.voice.SpeechDelivery?>(null)
                                val createdReportOutput = PiperVoiceOutput(ttsDirectory.path, engine = ttsEngine,
                                    modelSession = models,
                                    deliveryLedger = com.battlesbudz.jarvis.v2.voice.SpeechDeliveryLedger(reportReplyId) { delivery ->
                                        reportTerminal.set(delivery)
                                        voiceSessionController.updateDelivery(expectedCallId, delivery)
                                    },
                                    onPlaybackEnded = { callResources.playbackEnded(System.nanoTime() / 1_000_000) },
                                    acknowledgeDelays = false,
                                    log = { diagnosticRecorder.recordImportant("Accepted report TTS: $it") })
                                reportOutput = createdReportOutput
                                activeVoiceOutput = createdReportOutput
                                val reportJob = async {
                                    try {
                                        createdReportOutput.speak(kotlinx.coroutines.flow.flowOf(
                                            cleanSpeechText(report.reports.joinToString(" ") { it.text })
                                        ))
                                        true
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        // Parent/end-call cancellation still escapes; an interrupted
                                        // report attempt remains pending for the same session.
                                        if (!voiceSessionArmed) throw cancelled
                                        false
                                    } catch (failure: Throwable) {
                                        diagnosticRecorder.recordImportant("Accepted report delivery failed: ${failure.javaClass.simpleName}")
                                        false
                                    }
                                }
                                var reportPlaybackSucceeded = false
                                // Keep the same selector live during playback: a typed scoped
                                // cancellation/action must not wait for unbounded report audio.
                                val reportEvent = kotlinx.coroutines.selects.select<Int> {
                                    reportJob.onAwait { reportPlaybackSucceeded = it; 0 }
                                    followup.onAwait { captured = it; activeFollowup = null; 1 }
                                    typedAvailable.onAwait {
                                        typedInput = callInputQueue.claim(expectedCallId, ::typedDispatchable)
                                        2
                                    }
                                }
                                withContext(kotlinx.coroutines.NonCancellable) {
                                    when (reportEvent) {
                                        // Spoken/typed input interrupts this report attempt; its
                                        // ledger remains pending for a fresh delivery, never blocks
                                        // the capture/control selector behind playback.
                                        1, 2 -> reportJob.cancelAndJoin()
                                    }
                                }
                                val reportCompleted = reportEvent == 0 && reportPlaybackSucceeded &&
                                    reportTerminal.get()?.state == com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED
                                if (reportCompleted) {
                                    actionSession.markDelivered(report.attemptId, report.reports.mapTo(linkedSetOf()) { it.taskId })
                                } else {
                                    // STOP_REPLY, Barge-in, and delivery failure retain this exact
                                    // pending aggregate for a fresh ledger attempt; no action repeats.
                                    actionSession.interruptDelivery(report.attemptId)
                                }
                                createdReportOutput.release()
                                if (activeVoiceOutput === createdReportOutput) activeVoiceOutput = output
                                reportOutput = null
                                if (reportEvent == 2) typedInput?.let { typed ->
                                    activePumpTypedInput.set(typed)
                                    captured = com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                                        typed.text, byteArrayOf(), utteranceId = typed.id,
                                        capturedAtMs = typed.capturedAtMs,
                                        origin = com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED
                                    )
                                }
                                if (reportEvent == 0 && actionSession.isCaptureInProgress()) {
                                    // The capture stays owned by the next selector; do not await it
                                    // here or typed controls would starve behind a spoken floor.
                                    withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                                    withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                                    withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                                    continue@actionPump
                                } else if (reportEvent == 0 && captured == null) {
                                    // Retain the idle listener; a late capture-start owns its final.
                                    withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                                    withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                                    withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                                    if (reportCompleted && actionSession.tryDetachIdleCapture()) {
                                        withContext(kotlinx.coroutines.NonCancellable) { followup.cancelAndJoin() }
                                        activeFollowup = null
                                        break@actionPump
                                    }
                                    continue@actionPump
                                }
                            } else if (actionSession.isCaptureInProgress()) {
                                // Keep the confirmed speech future alive for the next selector so
                                // typed controls remain dispatchable while it finalizes.
                                withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                                withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                                withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                                continue@actionPump
                            } else {
                                // Retain the idle listener; a late capture-start owns its final.
                                withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                                withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                                withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                                continue@actionPump
                            }
                        }
                        withContext(kotlinx.coroutines.NonCancellable) { typedAvailable.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { deliveryReady.cancelAndJoin() }
                        withContext(kotlinx.coroutines.NonCancellable) { workerIdle?.cancelAndJoin() }
                        // A very fast executor can be terminal before its first listener select.
                        // Publish its durable report before classifying an idle ordinary capture.
                        if (pumpEvent == com.battlesbudz.jarvis.v2.voice.ActionPumpEvent.CAPTURED &&
                            !terminalReportsPublished && !acceptedVoiceActions.hasUnfinished()) {
                            publishTerminalActionReports(actionSession, expectedCallId)
                            terminalReportsPublished = true
                        }
                        val finalCaptured = captured ?: continue
                        val typedOwned = typedInput?.takeIf {
                            finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED &&
                                it.id == finalCaptured.utteranceId
                        }
                        if (finalCaptured.recognitionIssue == null && finalCaptured.transcript.isNotBlank()) {
                            try {
                                captureFinalMemory(finalCaptured.utteranceId, conversationHistory.current.value.id,
                                    expectedCallId, if (finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED)
                                        ConversationMemorySource.TEXT else ConversationMemorySource.VOICE,
                                    finalCaptured.transcript, finalCaptured.capturedAtMs)
                            } catch (failure: Throwable) {
                                if (typedOwned != null && callInputQueue.terminalize(typedOwned)) {
                                    activePumpTypedInput.compareAndSet(typedOwned, null)
                                    voiceSessionController.recordTerminalInputForCall(typedOwned.callId, typedOwned.id,
                                        "Cancelled before processing typed message: ${typedOwned.text}")
                                }
                                diagnosticRecorder.recordImportant("Accepted-pump typed memory capture failed: ${failure.javaClass.simpleName}")
                                continue@actionPump
                            }
                        }
                        if (finalCaptured.recognitionIssue == null &&
                            com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.isGoodbye(finalCaptured.transcript)) {
                            var admittedGoodbye = false
                            val promoted = typedOwned?.let { typed ->
                                callInputQueue.promote(typed) {
                                    voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                    admittedGoodbye = true
                                }
                            } ?: run {
                                voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                admittedGoodbye = true
                                true
                            }
                            if (!promoted || !admittedGoodbye) continue@actionPump
                            typedOwned?.let { activePumpTypedInput.compareAndSet(it, null) }
                            endVoiceCall()
                            diagnosticRecorder.recordImportant("Accepted action mode: spoken goodbye detached capture; work retained")
                            break@actionPump
                        }
                        val finalCapture = com.battlesbudz.jarvis.v2.voice.SessionCapture(
                            finalCaptured.utteranceId, finalCaptured.transcript, finalCaptured.recognitionIssue,
                            finalCaptured.wav, finalCaptured.audioIsComplete, finalCaptured.capturedAtMs, finalCaptured.origin
                        )
                        val plan = if (finalCaptured.recognitionIssue == null)
                            turnOrchestrator.plan(finalCaptured.transcript, voiceHistory.map { it.role to it.text }).actionPlan
                        else ActionTurnPlan.NotAction
                        val control = actionSession.control(finalCaptured.transcript, acceptedVoiceActions.hasUnfinished())
                        val kind = when {
                            finalCaptured.recognitionIssue != null -> com.battlesbudz.jarvis.v2.voice.CapturedKind.Ordinary
                            control != com.battlesbudz.jarvis.v2.voice.VoiceActionControl.None ->
                                com.battlesbudz.jarvis.v2.voice.CapturedKind.Control(control)
                            plan is ActionTurnPlan.Ready -> com.battlesbudz.jarvis.v2.voice.CapturedKind.AcceptedAction
                            plan is ActionTurnPlan.Rejected -> com.battlesbudz.jarvis.v2.voice.CapturedKind.RejectedAction
                            else -> com.battlesbudz.jarvis.v2.voice.CapturedKind.Ordinary
                        }
                        var leaveActionPump = false
                        fun applyCaptureOutcome(captureOutcome: com.battlesbudz.jarvis.v2.voice.CaptureOutcome) {
                            when (captureOutcome) {
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.Control -> {
                                    if (captureOutcome.value == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly) {
                                        activeVoiceOutput?.stopSpeaking()
                                        when (finalCaptured.transcript.trim().lowercase().trimEnd('.', '!', '?')) {
                                            "stop listening" -> leaveActionPump = true
                                            "pause microphone" -> {
                                                com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = true
                                                runtimeScope.launch { callResources.closeMicrophone() }
                                            }
                                            else -> diagnosticRecorder.recordImportant("Accepted action mode: speech delivery detached; work retained")
                                        }
                                    } else acceptedVoiceActions.cancel(captureOutcome.value) { it.value.callId == expectedCallId }
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.AcceptedAction -> {
                                    val followPlan = plan as ActionTurnPlan.Ready
                                    val followId = java.util.UUID.randomUUID().toString()
                                    voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                    voiceSessionController.beginReply(expectedCallId, followId)
                                    if (!enqueueAcceptedVoiceAction(actionSession, AcceptedVoiceInvocation(
                                            expectedCallId, followId, finalCaptured.utteranceId, finalCaptured.transcript, voiceHistory, followPlan))) {
                                        val explanation = "I already have accepted phone results waiting to be reported. Please wait a moment."
                                        voiceSessionController.updateReplyText(expectedCallId, followId, explanation, finished = true)
                                        actionSession.offerLocalFeedback(explanation)
                                    } else terminalReportsPublished = false
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.DeferredConversation -> {
                                    diagnosticRecorder.recordImportant("Accepted action mode: ordinary follow-up retained for post-queue normal turn")
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.ConversationReady -> {
                                    // An ordinary barge-in never discards already completed Android evidence.
                                    // Typed identity remains a queued handoff; spoken audio remains a correction.
                                    if (finalCaptured.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                                        pendingTypedHandoff.compareAndSet(null,
                                            com.battlesbudz.jarvis.v2.voice.CallFinalInput(finalCaptured.utteranceId, expectedCallId,
                                                conversationHistory.current.value.id, finalCaptured.transcript, finalCaptured.capturedAtMs))
                                    } else pendingVoiceCorrection.set(finalCaptured)
                                    if (actionSession.pendingReportCount() == 0) leaveActionPump = true
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.RejectedAction -> {
                                    val replyId = "local-" + java.util.UUID.randomUUID().toString()
                                    voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                    voiceSessionController.beginReply(expectedCallId, replyId)
                                    val explanation = (plan as ActionTurnPlan.Rejected).reason
                                    voiceSessionController.updateReplyText(expectedCallId, replyId, explanation, finished = true)
                                    actionSession.offerLocalFeedback(explanation)
                                    diagnosticRecorder.recordImportant("Accepted action mode: rejected follow-up did not enqueue work")
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.RecognitionIssue -> {
                                    val replyId = "local-" + java.util.UUID.randomUUID().toString()
                                    voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                    voiceSessionController.beginReply(expectedCallId, replyId)
                                    val explanation = "I couldn't retain that follow-up reliably. Please repeat it after these phone actions finish."
                                    voiceSessionController.updateReplyText(expectedCallId, replyId, explanation, finished = true)
                                    actionSession.offerLocalFeedback(explanation)
                                }
                                is com.battlesbudz.jarvis.v2.voice.CaptureOutcome.ConversationBusy -> {
                                    val replyId = "local-" + java.util.UUID.randomUUID().toString()
                                    voiceSessionController.appendTranscript("You", finalCaptured.transcript, origin = finalCaptured.origin)
                                    voiceSessionController.beginReply(expectedCallId, replyId)
                                    val explanation = "I kept your earlier follow-up; please repeat this later request after it is answered."
                                    voiceSessionController.updateReplyText(expectedCallId, replyId, explanation, finished = true)
                                    actionSession.offerLocalFeedback(explanation)
                                }
                                com.battlesbudz.jarvis.v2.voice.CaptureOutcome.Duplicate -> Unit
                            }
                        }
                        val admitted = typedOwned?.let { typed ->
                            callInputQueue.promote(typed) {
                                applyCaptureOutcome(actionSession.onTyped(finalCapture, kind))
                            }
                        } ?: run {
                            applyCaptureOutcome(actionSession.onCaptured(finalCapture, kind))
                            true
                        }
                        if (!admitted) continue@actionPump
                        typedOwned?.let { activePumpTypedInput.compareAndSet(it, null) }
                        if (leaveActionPump) {
                            if (control == com.battlesbudz.jarvis.v2.voice.VoiceActionControl.SpeechOnly &&
                                finalCaptured.transcript.trim().lowercase().trimEnd('.', '!', '?') == "stop listening") {
                                endVoiceCall()
                            }
                            break@actionPump
                        }
                    }
                    acceptedVoiceActions.awaitIdle()
                    actionSession.takeDeferredConversationIfNoCapture()?.let { deferred ->
                        if (deferred.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                            // Preserve TEXT identity/FIFO ownership; it must re-enter the typed
                            // owner rather than masquerading as an audio correction.
                            val handoff = com.battlesbudz.jarvis.v2.voice.CallFinalInput(deferred.utteranceId, expectedCallId,
                                conversationHistory.current.value.id, deferred.text, deferred.capturedAtMs)
                            if (!callInputQueue.publishHandoff(handoff) {
                                    pendingTypedHandoff.compareAndSet(null, handoff)
                                }) {
                                voiceSessionController.recordTerminalInputForCall(handoff.callId, handoff.id,
                                    "Cancelled before processing typed message: ${handoff.text}")
                            }
                        } else pendingVoiceCorrection.set(com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn(
                            deferred.text, deferred.wav, deferred.audioIsComplete,
                            deferred.recognitionIssue, deferred.utteranceId, deferred.capturedAtMs, deferred.origin
                        ))
                    }
                    val summary = acceptedVoiceSummary(expectedCallId)
                    voiceSessionController.setStateIfCurrent(expectedCallId, VoiceSessionState.ACTIVELY_LISTENING)
                    mainHandler.post { onTranscript("Jarvis", summary, true) }
                    finalMessage = "Voice Call accepted actions complete. Jarvis: $summary"
                    return true
                }
                if (runAcceptedActionMode()) return@launch
                val outcome = com.battlesbudz.jarvis.v2.voice.runInterruptibleReply(
                    reply = {
                        output.updateWaitStage(com.battlesbudz.jarvis.v2.voice.DelayedAcknowledgement.Stage.GENERATING)
                        turnTrace.mark(com.battlesbudz.jarvis.v2.voice.VoiceTurnTrace.Stage.REPLY_DISPATCHED)
                        val coordinator = VoiceTurnCoordinator(voiceSessionController)
                        // Queued Main callbacks retain immutable ticket authority after answer
                        // resources release. A bound answer must fail closed, never become unguarded.
                        val publicationGuard = com.battlesbudz.jarvis.v2.memory.MemoryPublicationGuard(memoryDeliveryFence)
                        fun publishBound(block: () -> Unit): Boolean = publicationGuard.publish(block)
                        val response = coordinator.processTurn(transcript, replyId = asrTurnId, publish = ::publishBound) { onToken ->
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
                            } else {
                                runConversationInternal(
                                prompt = transcript, history = voiceHistory, imageUri = null,
                                incrementalVoice = preparedText, voiceAudio = audioBytes, voiceAudioIsComplete = audioIsComplete,
                                comparison = comparison,
                                onLatency = { replyLatency.set(it) },
                                onMemoryBound = { ticket, context ->
                                    val binding = MemoryVoiceBinding(ticket, context, output, speechJob, expectedCallId, asrTurnId, answerExpiryJob)
                                    answerMemoryBinding.set(binding)
                                    publicationGuard.bind(ticket) {
                                        context.isCurrent() && voiceSessionController.currentCallId() == expectedCallId && voiceSessionArmed
                                    }
                                    memoryVoiceBinding.set(binding)
                                    context.expiresAtMs?.let { expiry ->
                                        val expiryJob = runtimeScope.launch {
                                            kotlinx.coroutines.delay((expiry - System.currentTimeMillis()).coerceAtLeast(0L))
                                            // Compare with the immutable binding captured for this answer. A normal
                                            // cleanup may have cleared the turn-local reference; it must never turn
                                            // a null/null CAS into revoking a later answer's output.
                                            if (!memoryDeliveryFence.isValid(ticket) && memoryVoiceBinding.compareAndSet(binding, null)) {
                                                binding.speechJob?.cancel()
                                                binding.output.stopSpeaking()
                                            }
                                        }
                                        answerExpiryJob.set(expiryJob)
                                    }
                                },
                                onActionResult = { name, message, succeeded ->
                                    voiceSessionController.recordReplyAction(expectedCallId, asrTurnId,
                                        com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome(name, message, succeeded))
                                },
                                onToken = { token ->
                                    publishBound {
                                        recordFirstText(token)
                                        onToken(token)
                                        streamed.append(token)
                                        mainHandler.post { publishBound { onTranscript("Jarvis", token, false) } }
                                        speechChunks.trySend(cleanSpeechText(token))
                                    }
                                },
                                onComplete = { text ->
                                    // Guarded/tool replies may arrive only through completion, with no token callback.
                                    // Keep the delivery flag armed through TTS so a later approved mutation can
                                    // stop queued audio as well as generation tokens.
                                    publishBound {
                                        recordFirstText(text)
                                        if (streamed.isBlank() && text.isNotBlank()) speechChunks.trySend(cleanSpeechText(text))
                                    }
                                    completed.complete(text)
                                }
                                )
                            }
                            val text = completed.await()
                            if (!interruptionTest && recognitionIssue == null) conversationJob?.join()
                            mainHandler.post { publishBound { onTranscript("Jarvis", text, true) } }
                            com.battlesbudz.jarvis.v2.ai.GenerationResult(text, -1L, null)
                        }
                        // The same binding guards the persisted/coordinator completion, not only
                        // the streaming callback. A mutation must not let buffered old text replace
                        // the safe terminal outcome after its delivery ticket was detached.
                        if (!publishBound {
                                voiceSessionController.updateReplyText(expectedCallId, asrTurnId, response.text,
                                    finished = true, latency = replyLatency.get())
                            }) {
                            // The old bound reply is revoked; replace any partial with an explicit
                            // safe terminal outcome on the saved call/reply identity.
                            voiceSessionController.updateReplyText(expectedCallId, asrTurnId,
                                "Memory changed while I was responding. Please ask again.", finished = true)
                        }
                        speechChunks.close()
                        speechJob?.join()
                        val releasedBinding = answerMemoryBinding.getAndSet(null)
                        if (releasedBinding != null) memoryVoiceBinding.compareAndSet(releasedBinding, null)
                        answerExpiryJob.getAndSet(null)?.cancel()
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
                relinquishUnpromotedTypedInput()
                // Keep the same call/context. Only an unfinished user utterance is discarded.
                if (voiceSessionController.currentCallId() != null) resumeCommandCue.set(true)
                else returnToWakeCuePending.set(true)
                diagnosticRecorder.recordImportant("Microphone yielded during capture; call=${voiceSessionController.currentCallId()} retained=true partial_discarded=true")
                finalMessage = "Paused — microphone interrupted; previous listening mode retained."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                relinquishUnpromotedTypedInput()
                if (cancelled is com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation) {
                    microphoneYielded = true // Use the same cleanup-before-rearm path.
                    diagnosticRecorder.recordImportant("Voice control requested: ${cancelled.control}")
                    preserveCaptureOnCancellation = cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY
                    if (cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) {
                        endVoiceCall()
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
                (listOfNotNull(queuedTypedInput, activePumpTypedInput.getAndSet(null))).distinctBy { it.id }.forEach { typed ->
                    if (callInputQueue.terminalize(typed)) {
                        voiceSessionController.recordTerminalInputForCall(typed.callId, typed.id,
                            "Cancelled before processing typed message: ${typed.text}")
                    }
                }
                diagnosticRecorder.record("Voice turn failed: ${error.stackTraceToString().take(4000)}")
                runCatching { voiceSessionController.interrupt() }
                finalMessage = "Voice Call turn failed: ${error.message ?: "unknown error"}"
            } finally {
                val cancelled = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive != true
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (cancelled && !acceptedVoiceActions.hasUnfinished()) { conversationJob?.cancel(); conversationJob?.join() }
                    try {
                        promotionLease.releaseIfUnadmitted(::releaseAcceptedVoiceLeaseIfIdle)
                        relinquishUnpromotedTypedInput()
                        // A cancelled/failed pump may have claimed a final typed input after its
                        // last local branch. Terminalize it here; End already drained it when End won.
                        activePumpTypedInput.getAndSet(null)?.let { typed ->
                            if (callInputQueue.terminalize(typed)) {
                                voiceSessionController.recordTerminalInputForCall(typed.callId, typed.id,
                                    "Cancelled before processing typed message: ${typed.text}")
                            }
                        }
                        runCatching { capture?.stop() }
                        runCatching { microphone?.stop() }
                        preparation?.close()
                        if (!acceptedVoiceActions.hasUnfinished()) conversationEngine?.onPromptSubmitted = { _, _ -> }
                    } finally {
                        speechChunks.close()
                        runCatching { voiceOutput?.stopSpeaking() }
                        speechJob?.cancel()
                        speechJob?.join()
                        runCatching { voiceSessionController.flushCheckpoint() }
                            .onFailure { diagnosticRecorder.recordImportant("Voice checkpoint flush failed: ${it.javaClass.simpleName}") }
                        runCatching { voiceOutput?.release() }
                        // Common terminal ownership release: success, cancellation, and failure
                        // all clear only this answer's binding/deadline/output references.
                        answerMemoryBinding.getAndSet(null)?.let { binding ->
                            binding.expiryJob.getAndSet(null)?.cancel()
                            memoryVoiceBinding.compareAndSet(binding, null)
                        }
                        finalSpeechDelivery.get()?.let { turnOrchestrator.reconcileVoiceDelivery(it.deliveredText) }
                        if (activeVoiceOutput === voiceOutput) activeVoiceOutput = null
                        if (activeVoiceCapture === capture) activeVoiceCapture = null
                        if (!acceptedVoiceActions.hasUnfinished()) activeContinuousActionSession = null
                        val callEnded = !voiceSessionArmed || voiceSessionController.currentCallId() == null ||
                            (expectedResourceCall != null && voiceSessionController.currentCallId() != expectedResourceCall) ||
                            finalMessage.contains("turn failed", true)
                        try {
                            if (callEnded || com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value ||
                                cancelled && !preserveCaptureOnCancellation) callResources.closeMicrophone()
                            if (callEnded) {
                                com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence.finish()
                                // Accepted native work owns the resident engine until its exact task Job joins.
                                if (!acceptedVoiceActions.hasUnfinished()) callResources.closeModels()
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

    /**
     * Schedules an already parsed final voice action. The callback uses saved call/reply IDs so a
     * result remains durable after an End Call UI transition and can never attach to a newer call.
     */
    private fun enqueueAcceptedVoiceAction(
        actionSession: com.battlesbudz.jarvis.v2.voice.ContinuousActionSession<AcceptedVoiceInvocation>,
        invocation: AcceptedVoiceInvocation
    ): Boolean {
        // Every admission advances a shared generation. An older idle observer may release only
        // if no later admission has occurred while it was suspended in awaitIdle().
        val leaseGeneration = retainAcceptedVoiceLease() ?: return false
        // Reserve this task's eventual terminal report before admission. The reservation survives
        // queue completion until a real playback delivery acknowledges it.
        if (!actionSession.reserveActionAdmission(invocation.replyId)) {
            releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
            return false
        }
        val admitted = acceptedVoiceActions.admit(invocation.replyId, invocation.utteranceId, invocation)
        if (admitted == null || admitted.id != invocation.replyId) {
            actionSession.abandonActionAdmission(invocation.replyId)
            releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
            return false
        }
        acceptedVoiceActions.start { task ->
            val accepted = task.value
            val planned = accepted.plan.steps.map { it.request.name }
            val completedSteps = mutableListOf<String>()
            var allSucceeded = true
            voiceSessionController.updateTaskForCall(accepted.callId, com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus(
                com.battlesbudz.jarvis.v2.voice.VoiceTaskState.WAITING_FOR_USER,
                pendingSteps = planned
            ))
            // The action-mode admission transferred one lease for the complete FIFO batch.
            check(acceptedVoiceLeaseActive()) { "accepted_action_batch_missing_model_lease" }
            // This callback is installed only by the serial queue worker. It prevents a queued
            // task from inheriting the initial voice turn's diagnostic attribution.
            conversationEngine?.onPromptSubmitted = { submitted, audioSize ->
                diagnosticRecorder.recordInferencePrompt(
                    "acceptedTask=${task.id} replyId=${accepted.replyId} utteranceId=${accepted.utteranceId} " +
                        "callId=${accepted.callId} audioBytes=$audioSize\nsource=${accepted.prompt}\nsubmitted=$submitted"
                )
            }
            val completed = CompletableDeferred<String>()
            val job = runConversationInternal(
                prompt = accepted.prompt,
                history = accepted.history,
                imageUri = null,
                onToken = { /* Executor receipts, rather than draft model prose, are reported after completion. */ },
                onComplete = { completed.complete(it) },
                onActionResult = { name, message, succeeded ->
                    allSucceeded = allSucceeded && succeeded
                    completedSteps += name
                    voiceSessionController.recordReplyAction(accepted.callId, accepted.replyId,
                        com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome(name, message, succeeded))
                    voiceSessionController.updateTaskForCall(accepted.callId, com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus(
                        if (succeeded) com.battlesbudz.jarvis.v2.voice.VoiceTaskState.WAITING_FOR_USER
                        else com.battlesbudz.jarvis.v2.voice.VoiceTaskState.FAILED,
                        completedSteps.toList(), planned.drop(completedSteps.size)
                    ))
                },
                frozenActionPlan = accepted.plan,
                frozenVoiceFinal = true
            )
            if (job == null) {
                val terminal = "Failed; unattempted: ${planned.joinToString()}."
                voiceSessionController.updateTaskForCall(accepted.callId, com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus(
                    com.battlesbudz.jarvis.v2.voice.VoiceTaskState.FAILED, emptyList(), planned
                ))
                voiceSessionController.updateTerminalReplyTextForCall(accepted.callId, accepted.replyId, terminal)
                return@start false
            }
            // The exact invocation handle, not mutable conversationJob, is the queue completion
            // boundary. Cancellation joins this handle before the next accepted task begins.
            try {
                completed.await()
                job.join()
                completedSteps.size == planned.size && allSucceeded
            } finally {
                val cancelledBeforeCleanup = !kotlin.coroutines.coroutineContext.isActive
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!job.isCompleted) job.cancel()
                    job.join()
                    val state = when {
                        completedSteps.size == planned.size && allSucceeded -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.COMPLETED
                        cancelledBeforeCleanup -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.CANCELLED
                        else -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.FAILED
                    }
                    val remaining = planned.drop(completedSteps.size)
                    voiceSessionController.updateTaskForCall(accepted.callId, com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus(
                        state, completedSteps.toList(), remaining
                    ))
                    val terminal = when (state) {
                        com.battlesbudz.jarvis.v2.voice.VoiceTaskState.COMPLETED -> ""
                        com.battlesbudz.jarvis.v2.voice.VoiceTaskState.CANCELLED -> "Cancelled; unattempted: ${remaining.joinToString()}."
                        else -> "Failed; unattempted: ${remaining.joinToString()}."
                    }
                    val receipts = acceptedVoiceSummary(accepted.callId, setOf(accepted.replyId))
                    voiceSessionController.updateTerminalReplyTextForCall(accepted.callId, accepted.replyId,
                        listOf(receipts, terminal).filter { it.isNotBlank() }.joinToString(" "))
                }
            }
        }
        releaseAcceptedVoiceLeaseAfterIdle(leaseGeneration)
        return true
    }

    /** Retain or transfer a batch lease and publish a generation for its idle observer. */
    private fun retainAcceptedVoiceLease(): Long? = acceptedVoiceLease.retain(modelStore::tryBeginModelOperation)

    /** Called only while the initial voice turn already owns ModelStore's lease. */
    private fun transferAcceptedVoiceLease(): Long = acceptedVoiceLease.transfer()

    private fun acceptedVoiceLeaseActive(): Boolean = acceptedVoiceLease.active()

    private fun releaseAcceptedVoiceLeaseAfterIdle(generation: Long) {
        runtimeScope.launch {
            acceptedVoiceActions.awaitIdle()
            if (acceptedVoiceLease.releaseIfCurrent(generation, !acceptedVoiceActions.hasUnfinished())) {
                modelStore.endModelOperation()
            }
        }
    }

    /** Failed admission after a transferred initial lease has no task to drain. */
    private fun releaseAcceptedVoiceLeaseIfIdle() =
        releaseAcceptedVoiceLeaseAfterIdle(acceptedVoiceLease.generation())

    private fun publishTerminalActionReports(
        session: com.battlesbudz.jarvis.v2.voice.ContinuousActionSession<AcceptedVoiceInvocation>, callId: String
    ) {
        acceptedVoiceActions.tasks.value.filter { it.value.callId == callId && it.state.isTerminalActionState() }
            .forEach { task ->
                val terminalText = persistTerminalActionReply(task)
                if (!session.onTaskEvent(task.id, terminalText))
                    diagnosticRecorder.recordImportant("Accepted terminal report remains durable pending retry task=${task.id}")
            }
    }

    /** Saved-ID terminal text is authoritative even when no voice session remains to speak it. */
    private fun persistTerminalActionReply(
        task: com.battlesbudz.jarvis.v2.actions.AcceptedActionTask<AcceptedVoiceInvocation>
    ): String {
        val invocation = task.value
        val callId = invocation.callId
        val receipts = acceptedVoiceSummary(callId, setOf(invocation.replyId))
        val completed = voiceCallStore.list().firstOrNull { it.id == callId }?.transcript
            ?.firstOrNull { it.replyId == invocation.replyId }?.actions.orEmpty()
        val remaining = invocation.plan.steps.map { it.request.name }.drop(completed.size)
        val terminal = when (task.state) {
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.CANCELLED -> "Cancelled; unattempted: ${remaining.joinToString()}."
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.INTERRUPTED -> "Interrupted; unattempted: ${remaining.joinToString()}."
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.FAILED -> "Failed; unattempted: ${remaining.joinToString()}."
            else -> ""
        }
        val terminalState = when (task.state) {
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.COMPLETED -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.COMPLETED
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.CANCELLED,
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.INTERRUPTED -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.CANCELLED
            else -> com.battlesbudz.jarvis.v2.voice.VoiceTaskState.FAILED
        }
        val terminalText = listOf(receipts, terminal).filter { it.isNotBlank() }.joinToString(" ")
        voiceSessionController.updateTaskForCall(callId, com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus(
            terminalState, completed.map { it.name }, remaining
        ))
        voiceSessionController.updateTerminalReplyTextForCall(callId, invocation.replyId, terminalText)
        return terminalText
    }

    private fun com.battlesbudz.jarvis.v2.actions.AcceptedActionState.isTerminalActionState(): Boolean =
        this in setOf(com.battlesbudz.jarvis.v2.actions.AcceptedActionState.COMPLETED,
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.FAILED,
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.CANCELLED,
            com.battlesbudz.jarvis.v2.actions.AcceptedActionState.INTERRUPTED)

    private fun acceptedVoiceSummary(callId: String, onlyReplyIds: Set<String>? = null): String {
        val replyIds = onlyReplyIds ?: acceptedVoiceActions.tasks.value.map { it.value }
            .filter { it.callId == callId }.map { it.replyId }.toSet()
        val outcomes = voiceCallStore.list().firstOrNull { it.id == callId }?.transcript
            ?.filter { it.replyId in replyIds }?.flatMap { it.actions }.orEmpty()
        return outcomes.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.message }
            ?: "I couldn't complete the accepted phone action."
    }

    fun onServiceStopped() {
        endVoiceCall()
        val previousVoice = voiceTurnJob
        val previousConversation = conversationJob
        runtimeScope.launch {
            previousVoice?.join()
            previousConversation?.join()
            acceptedVoiceActions.awaitIdle()
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
        val endedCallId = voiceSessionController.currentCallId()
        // Close the shared queue gate before inspecting deferred handoffs. A promotion either
        // completed its transfer before this drain, or sees End and cannot create a new handoff.
        callInputQueue.end(endedCallId ?: "").forEach { input ->
            diagnosticRecorder.recordImportant("Typed Voice Call input not processed because call ended: event=${input.id}")
            runCatching { voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                "Cancelled before processing typed message: ${input.text}") }
        }
        pendingVoiceCorrection.getAndSet(null)?.let { deferred ->
            runCatching { voiceSessionController.appendTranscript("Jarvis", "Cancelled before processing voice follow-up: ${deferred.transcript}") }
        }
        activeContinuousActionSession?.cancelDeferredConversation()?.let { deferred ->
            if (deferred.origin == com.battlesbudz.jarvis.v2.voice.TranscriptOrigin.TYPED) {
                endedCallId?.let { callId -> runCatching { voiceSessionController.recordTerminalInputForCall(callId, deferred.utteranceId,
                    "Cancelled before processing typed message: ${deferred.text}") } }
            } else runCatching { voiceSessionController.appendTranscript("Jarvis", "Cancelled before processing voice follow-up: ${deferred.text}") }
        }
        pendingTypedHandoff.getAndSet(null)?.takeIf { it.callId == endedCallId }?.let { input ->
            runCatching { voiceSessionController.recordTerminalInputForCall(input.callId, input.id,
                "Cancelled before processing typed message: ${input.text}") }
        }
        voiceSessionArmed = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.paused.value = false
        com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        stopVoiceService()
        runtimeScope.launch { callResources.closeMicrophone() }
        activeVoiceOutput?.stopSpeaking()
        activeContinuousActionSession?.detach()
        activeContinuousActionSession = null // Detached call reports remain durable; never block a new call.
        voiceTurnJob?.cancel()
        // End Call detaches microphone/TTS immediately but does not retract a bounded accepted
        // task. Its saved-ID callbacks continue to checkpoint evidence for this original call.
        if (!acceptedVoiceActions.hasUnfinished()) conversationJob?.cancel()
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
