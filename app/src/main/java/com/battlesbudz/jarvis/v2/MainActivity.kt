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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.VoiceSessionController
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceTurnCoordinator
import com.battlesbudz.jarvis.v2.voice.KokoroModelStore
import com.battlesbudz.jarvis.v2.voice.SherpaKokoroVoiceOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

data class ChatEntry(
    val role: String,
    val text: String,
    val imageUri: String? = null
)

class MainActivity : ComponentActivity() {
    internal companion object {
        val activeConversationJobs = AtomicInteger(0)
        internal const val SHORT_TERM_SUMMARY_KEY = "short_term_summary"
        // This is an app-side character budget, not Gemma's advertised
        // context maximum. It leaves room for a normal answer before the
        // bounded native conversation is reset and reseeded from app context.
        internal const val CONVERSATION_COMPACTION_LIMIT = 10_000
        internal const val GENERATION_HEADROOM = 2_000
        internal const val MAX_USER_PROMPT_CHARS = 12_000
        internal const val INTERRUPTED_RESPONSE = "The previous response was interrupted. Please send that again."
    }

    internal val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    private var activeVoiceCapture: AudioTurnCapture? = null
    private data class PendingVoiceTurn(
        val start: Boolean,
        val report: (String) -> Unit,
        val onTranscript: (String, String, Boolean) -> Unit,
        val onFinished: (String) -> Unit
    )
    private var pendingVoiceTurn: PendingVoiceTurn? = null
    private var pendingVoiceTest: Pair<(String) -> Unit, (String) -> Unit>? = null
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingVoiceTest
        pendingVoiceTest = null
        if (pending == null) return@registerForActivityResult
        if (granted) {
            runDirectAudioSmokeTest(pending.first, pending.second)
        } else {
            pending.first("Microphone permission is required for the direct E2B audio test.")
            pending.second("Microphone permission is required for the direct E2B audio test.")
        }
    }
    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingVoiceTurn
        pendingVoiceTurn = null
        if (pending == null) return@registerForActivityResult
        if (granted) {
            runVoiceTurn(pending.start, pending.report, pending.onTranscript, pending.onFinished)
        } else {
            pending.report("Microphone permission is required for Voice Calls.")
            pending.onFinished("Voice Call could not start because microphone permission was denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = ModelStore(applicationContext)
        kokoroModelStore = KokoroModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
        voiceCallStore = SharedPreferencesVoiceCallStore(
            getSharedPreferences("voice_calls", MODE_PRIVATE)
        )
        voiceSessionController = VoiceSessionController(voiceCallStore)
        diagnosticRecorder = com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder(sessionPreferences)
        val interruptedSession = sessionPreferences.getBoolean("sending", false)
        shortTermContext.restoreSummary(
            if (interruptedSession) null else {
                savedInstanceState?.getString(SHORT_TERM_SUMMARY_KEY)
                    ?: sessionPreferences.getString(SHORT_TERM_SUMMARY_KEY, null)
            }
        )
        if (interruptedSession) {
            // Do not reuse context captured while the native engine was being
            // torn down. The visible transcript remains recoverable.
            sessionPreferences.edit().remove(SHORT_TERM_SUMMARY_KEY).apply()
        }
        diagnosticRecorder.restore()
        setContent {
            JarvisApp(
                store = modelStore,
                voiceModelStore = kokoroModelStore,
                initialMessages = restoreTranscript(),
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onRunDirectAudioTest = { report, onFinished ->
                    runDirectAudioSmokeTest(report, onFinished)
                },
                onRunDirectAudioToolTest = { report, onFinished ->
                    runDirectAudioToolSmokeTest(report, onFinished)
                },
                onVoiceTurn = { start, report, onTranscript, onFinished ->
                    runVoiceTurn(start, report, onTranscript, onFinished)
                },
                onEndVoiceCall = { report -> endVoiceCall(report) },
                onDownloadGemma = { onProgress, onStatus, onFinished ->
                    downloadGemmaAndTest(onProgress, onStatus, onFinished)
                },
                onImportModel = { uri, spec, report -> importModel(uri, spec, report) },
                onCopyDiagnostics = { transcript -> copyDiagnostics(transcript) },
                onMessagesChanged = { persistTranscript(it) },
                onSendingChanged = { sessionPreferences.edit().putBoolean("sending", it).apply() },
                onSend = { prompt, imageUri, history, onToken, onComplete ->
                    runConversation(prompt, history, imageUri, onToken, onComplete)
                }
            )
        }
    }

    /** Runs one explicit live Voice Call turn without persisting raw audio. */
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

        if (start) {
            if (activeVoiceCapture != null) {
                report("A Voice Call turn is already listening.")
                return
            }
            activeVoiceCapture = AudioTurnCapture(
                AndroidAudioInput(
                    lifecycleScope,
                    com.battlesbudz.jarvis.v2.voice.AudioFormat()
                ),
                lifecycleScope
            )
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    if (voiceSessionController.state.value == VoiceSessionState.PASSIVE_LISTENING) {
                        voiceSessionController.beginCall()
                    }
                    activeVoiceCapture?.start()
                    mainHandler.post {
                        report("Voice Call is listening. Tap again when you finish speaking.")
                    }
                } catch (error: Throwable) {
                    activeVoiceCapture = null
                    mainHandler.post {
                        val message = "Voice Call could not start: ${error.message ?: "unknown error"}"
                        report(message)
                        onFinished(message)
                    }
                }
            }
            return
        }

        val capture = activeVoiceCapture
        if (capture == null) {
            val message = "There is no active Voice Call turn to send."
            report(message)
            onFinished(message)
            return
        }
        activeVoiceCapture = null
        lifecycleScope.launch(Dispatchers.Default) {
            var gemma: LiteRtLmEngine? = null
            var finalMessage = "Voice Call turn failed."
            try {
                mainHandler.post { report("Processing your Voice Call turn locally…") }
                val audioBytes = capture.stop()
                check(audioBytes.size > 44) { "No microphone audio was captured." }
                check(modelStore.tryBeginModelOperation()) {
                    "Another model operation is still finishing. Please try again in a moment."
                }
                try {
                    check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                        "The Gemma model file changed or failed integrity verification. Re-import it."
                    }
                    gemma = LiteRtLmEngine(
                        modelId = ModelCatalog.gemma4E2b.id,
                        modelPath = modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                        cacheDir = cacheDir.path,
                        useGpu = true,
                        audioEnabled = true
                    )
                    val activeGemma = gemma ?: error("Gemma audio runtime was not created.")
                    activeGemma.initialize()
                    mainHandler.post { report("Gemma is understanding the audio…") }
                    val streamedTranscript = StringBuilder()
                    val transcriptResult = activeGemma.generateAudio(
                        prompt = "Transcribe the user's speech. Return only the words you heard, with no explanation.",
                        audioBytes = audioBytes,
                        onToken = { token ->
                            streamedTranscript.append(token)
                            mainHandler.post {
                                report("Heard: ${streamedTranscript.toString().trim().takeLast(180)}")
                            }
                        }
                    )
                    diagnosticRecorder.record(
                        "Inference\n" +
                            "stage=voice_audio_transcription\n" +
                            "timeToFirstTokenMs=${transcriptResult.timeToFirstTokenMs}\n" +
                            "totalGenerationTimeMs=${transcriptResult.totalGenerationTimeMs}\n" +
                            "outputTokensEstimated=${transcriptResult.outputTokens ?: -1}\n" +
                            "streamEvents=${transcriptResult.streamEvents}\n" +
                            "decodeTokensPerSecondEstimated=${transcriptResult.decodeTokensPerSecond ?: -1.0}"
                    )
                    val transcript = transcriptResult.text.trim()
                    check(transcript.isNotBlank()) { "Gemma returned an empty transcription." }
                    val coordinator = VoiceTurnCoordinator(voiceSessionController)
                    val voiceOutput = check(kokoroModelStore.isReady()) {
                        "The local voice output model is still preparing. Please finish setup first."
                    }.let {
                        SherpaKokoroVoiceOutput(
                            modelDirectory = kokoroModelStore.directory().path,
                            log = { event -> diagnosticRecorder.record("Voice TTS: $event") }
                        )
                    }
                    mainHandler.post { onTranscript("You", transcript, true) }
                    val response = coordinator.processTurn(transcript) { onToken ->
                        mainHandler.post { report("Jarvis is responding…") }
                        // The audio-capable engine has completed the input turn.
                        // Release it before handing the text request to the
                        // existing conversation runtime, which owns tool
                        // routing, action validation, and context handling.
                        // Release the audio-capable engine before the text
                        // conversation starts, but mark it consumed so the
                        // outer cleanup cannot close the native engine twice.
                        runCatching { activeGemma.close() }
                        gemma = null
                        val completed = CompletableDeferred<String>()
                        val speechChunks = Channel<String>(Channel.UNLIMITED)
                        val speechJob = lifecycleScope.launch(Dispatchers.Default) {
                            try {
                                voiceOutput.speak(
                                    speechChunks.receiveAsFlow(),
                                    onChunkStarted = { phrase ->
                                        mainHandler.post { report("Jarvis is speaking… ${phrase.take(80)}") }
                                    }
                                )
                            } catch (error: Throwable) {
                                // Audio playback must never bring down the
                                // voice-call coroutine or the Activity. The
                                // text transcript remains authoritative.
                                diagnosticRecorder.record(
                                    "Voice TTS failure: ${error.stackTraceToString().take(4_000)}"
                                )
                                mainHandler.post {
                                    report("Jarvis answered in text, but local voice playback failed: ${error.message ?: "unknown audio error"}")
                                }
                            }
                        }
                        val streamedSpeech = StringBuilder()
                        try {
                            runConversation(
                                prompt = transcript,
                                history = emptyList(),
                                imageUri = null,
                                onToken = { token ->
                                    onToken(token)
                                    streamedSpeech.append(token)
                                    mainHandler.post { onTranscript("Jarvis", token, false) }
                                    speechChunks.trySend(token)
                                },
                                onComplete = {
                                    if (streamedSpeech.isBlank() && it.isNotBlank()) {
                                        speechChunks.trySend(it)
                                    }
                                    completed.complete(it)
                                }
                            )
                            // runConversation posts its completion callback to
                            // the main thread. Keep the speech channel open
                            // until that callback has supplied the final text.
                            val completedText = completed.await()
                            if (completedText.isNotBlank()) {
                                mainHandler.post { onTranscript("Jarvis", completedText, true) }
                            }
                        } finally {
                            speechChunks.close()
                            speechJob.join()
                            runCatching { voiceOutput.release() }
                        }
                        val completedText = completed.getCompleted()
                        com.battlesbudz.jarvis.v2.ai.GenerationResult(
                            text = completedText,
                            timeToFirstTokenMs = -1L,
                            decodeTokensPerSecond = null
                        )
                    }
                    // Tool/action turns can intentionally suppress streaming
                    // tokens. Persist the authoritative final response too,
                    // so the Voice Call transcript never loses a reply.
                    voiceSessionController.appendTranscript("Jarvis", response.text.trim(), complete = true)
                    mainHandler.post { report("Voice Call turn complete.") }
                    finalMessage = "Voice Call turn complete. Heard: $transcript\nJarvis: ${response.text.trim()}"
                } finally {
                    modelStore.endModelOperation()
                }
            } catch (error: Throwable) {
                runCatching { voiceSessionController.interrupt() }
                finalMessage = "Voice Call turn failed: ${error.message ?: "unknown error"}"
            } finally {
                gemma?.close()
                mainHandler.post {
                    report(finalMessage)
                    onFinished(finalMessage)
                }
            }
        }
    }

    private fun endVoiceCall(report: (String) -> Unit) {
        runCatching {
            if (voiceSessionController.state.value != VoiceSessionState.PASSIVE_LISTENING) {
                voiceSessionController.end()
            }
        }.onFailure { report("Voice Call could not be saved: ${it.message ?: "unknown error"}") }
            .onSuccess { report("Voice Call saved on this phone.") }
    }

    private fun runDirectAudioSmokeTest(
        report: (String) -> Unit,
        onFinished: (String) -> Unit
    ) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTest = report to onFinished
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!modelStore.tryBeginModelOperation()) {
            val message = "Another model operation is still finishing. Please try again in a moment."
            report(message)
            onFinished(message)
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            var gemma: LiteRtLmEngine? = null
            var finalMessage = "Direct E2B audio test failed."
            try {
                mainHandler.post { report("Recording a 25-second microphone sample…") }
                val audioBytes = recordVoiceSample()
                mainHandler.post { report("Loading Gemma 4 E2B audio runtime…") }
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                gemma = LiteRtLmEngine(
                    modelId = ModelCatalog.gemma4E2b.id,
                    modelPath = modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir = cacheDir.path,
                    useGpu = true,
                    audioEnabled = true
                )
                gemma.initialize()
                mainHandler.post { report("Sending audio directly to Gemma…") }
                val streamedTranscript = StringBuilder()
                val result = gemma.generateAudio(
                    prompt = "Transcribe the following speech segment. Return only the words you heard, with no explanation.",
                    audioBytes = audioBytes,
                    onToken = { token ->
                        streamedTranscript.append(token)
                        mainHandler.post {
                            report("Gemma transcript: ${streamedTranscript.toString().trim().takeLast(160)}")
                        }
                    }
                )
                val transcript = result.text.trim()
                check(transcript.isNotBlank()) { "Gemma returned an empty transcription." }
                finalMessage = "Direct E2B audio succeeded (${result.timeToFirstTokenMs} ms to first token). Transcript: $transcript"
            } catch (error: Throwable) {
                finalMessage = "Direct E2B audio test failed: ${error.message ?: "unknown error"}"
            } finally {
                gemma?.close()
                modelStore.endModelOperation()
                mainHandler.post {
                    report(finalMessage)
                    onFinished(finalMessage)
                }
            }
        }
    }

    private fun runDirectAudioToolSmokeTest(
        report: (String) -> Unit,
        onFinished: (String) -> Unit
    ) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceTest = report to onFinished
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!modelStore.tryBeginModelOperation()) {
            val message = "Another model operation is still finishing. Please try again in a moment."
            report(message)
            onFinished(message)
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            var gemma: LiteRtLmEngine? = null
            var finalMessage = "Direct E2B voice-tool test failed."
            try {
                mainHandler.post { report("Recording a 25-second voice command…") }
                val audioBytes = recordVoiceSample()
                mainHandler.post { report("Loading E2B with Jarvis tool schemas…") }
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                gemma = LiteRtLmEngine(
                    modelId = ModelCatalog.gemma4E2b.id,
                    modelPath = modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir = cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    audioEnabled = true
                )
                gemma.initialize()
                mainHandler.post { report("Asking E2B to select a Jarvis tool…") }
                val generated = gemma.generateAudio(
                    prompt = """
                        You are testing Jarvis voice tool calls. Listen to the user's spoken request.
                        If it requests a phone action, call exactly one matching tool from the available tools.
                        Use read_battery for battery questions, set_volume for media volume, and open_app for app launches.
                        Do not invent a tool. Do not execute anything yourself. If the request is not one of those actions, answer briefly without a tool.
                    """.trimIndent(),
                    audioBytes = audioBytes,
                    onToken = { token -> mainHandler.post { report("E2B response: ${token.trim()}") } }
                )
                val call = generated.toolCalls.singleOrNull()
                if (call == null) {
                    finalMessage = "No structured tool call detected. E2B text: ${generated.text.trim().ifBlank { "(empty)" }}"
                } else {
                    val simulatedResult = simulatedVoiceToolResult(call)
                    mainHandler.post {
                        report("Tool selected: ${call.name}\nArguments: ${call.arguments}\nSimulating result…")
                    }
                    val followUp = gemma.sendToolResult(
                        call,
                        simulatedResult,
                        onToken = { token -> mainHandler.post { report("Final response: ${token.trim()}") } }
                    )
                    finalMessage = "Voice tool test succeeded. Tool: ${call.name}; arguments: ${call.arguments}; simulated result: $simulatedResult; final response: ${followUp.text.trim()}"
                }
            } catch (error: Throwable) {
                finalMessage = "Direct E2B voice-tool test failed: ${error.message ?: "unknown error"}"
            } finally {
                gemma?.close()
                modelStore.endModelOperation()
                mainHandler.post {
                    report(finalMessage)
                    onFinished(finalMessage)
                }
            }
        }
    }

    private fun simulatedVoiceToolResult(call: com.battlesbudz.jarvis.v2.ai.ToolCall): String = when (call.name) {
        "read_battery" -> "{\"battery_percent\":87,\"charging\":false,\"status\":\"discharging\"}"
        "set_volume" -> "{\"success\":true,\"level\":40,\"note\":\"Simulated only; phone volume was not changed.\"}"
        "open_app" -> "{\"success\":true,\"app\":\"simulated\",\"note\":\"Simulated only; no application was opened.\"}"
        else -> "{\"success\":false,\"error\":\"Tool is not allowed in this simulation.\"}"
    }

    private fun recordVoiceSample(): ByteArray {
        val sampleRate = 16_000
        // Keep this below Gemma 4's documented 30-second audio clip limit
        // while allowing a realistic spoken batch for the first voice test.
        val durationMs = 25_000
        val samples = sampleRate * durationMs / 1_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "The microphone could not be initialized." }
        val bufferSize = maxOf(minBuffer, sampleRate / 2)
        val pcm = ByteArray(samples * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be initialized." }
        try {
            recorder.startRecording()
            var offset = 0
            while (offset < pcm.size) {
                val count = recorder.read(pcm, offset, pcm.size - offset)
                check(count > 0) { "The microphone stopped recording unexpectedly." }
                offset += count
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return com.battlesbudz.jarvis.v2.voice.WavEncoder.pcm16Mono(pcm, sampleRate)
    }

    private fun restoreTranscript(): List<ChatEntry> {
        val stored = sessionPreferences.getString("transcript", null).orEmpty()
        if (stored.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val text = item.optString("text")
                val imageUri = item.optString("imageUri").takeIf { it.isNotBlank() }
                if (role.isBlank() || text.isBlank()) null else ChatEntry(role, text, imageUri)
            }
        }.getOrDefault(emptyList())
    }

    private fun persistTranscript(messages: List<ChatEntry>) {
        val array = JSONArray()
        messages.takeLast(100).forEach { entry ->
            array.put(
                JSONObject()
                    .put("role", entry.role)
                    .put("text", entry.text)
                    .apply { entry.imageUri?.let { put("imageUri", it) } }
            )
        }
        sessionPreferences.edit().putString("transcript", array.toString()).apply()
    }

    private fun copyDiagnostics(transcript: List<ChatEntry>) {
        val visible = transcript.joinToString("\n\n") { "${it.role}: ${it.text}" }
        val diagnostics = "Jarvis OS V2 chat diagnostics\n\nVisible transcript:\n$visible\n\nRecent runtime turns:\n${diagnosticRecorder.snapshot()}"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis diagnostics", diagnostics))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
        sessionPreferences.edit()
            .putString(SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
            .apply()
        super.onSaveInstanceState(outState)
    }

    
    override fun onDestroy() {
        conversationJob?.cancel()
        val engine = conversationEngine
        conversationEngine = null
        if (engine != null) {
            conversationJob?.invokeOnCompletion {
                cleanupScope.launch { engine.close() }
            } ?: cleanupScope.launch { engine.close() }
        }
        super.onDestroy()
    }

    /** Reset the native conversation without tearing down the initialized Engine. */
    internal suspend fun resetNativeConversation() {
        conversationEngine?.resetConversation()
        nativeConversationHasContext = false
        conversationCharacters = 0
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
            mainHandler.post { report("Loading Gemma 4 E2B…") }
            var gemma: LiteRtLmEngine? = null
            var smokeTestSucceeded = false
            var finalMessage: String? = null
            try {
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                conversationEngine?.close()
                conversationEngine = null
                nativeConversationHasContext = false
                gemma = LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = MobileActionToolDefinitions.all(),
                    visionEnabled = true
                )
                gemma.initialize()
                val probe = gemma.generate(
                    "Reply with exactly GEMMA_PR1_OK and nothing else.",
                    onToken = {}
                )
                check(probe.text.trim() == "GEMMA_PR1_OK") {
                    "The selected Gemma file did not pass its identity probe."
                }
                smokeTestSucceeded = true
            } catch (error: Throwable) {
                finalMessage = "Gemma model test failed: ${error.message ?: "unknown error"}"
            } finally {
                gemma?.close()
                if (smokeTestSucceeded) {
                    modelStore.markSmokeTestPassed()
                    finalMessage = "Gemma 4 E2B initialized successfully."
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
                if (result.isSuccess && spec.id == ModelCatalog.gemma4E2b.id) {
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
        if (spec.id == ModelCatalog.gemma4E2b.id) {
            importJob.invokeOnCompletion {
                conversationEngine?.close()
                conversationEngine = null
                nativeConversationHasContext = false
            }
        }
    }

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

    private fun runConversation(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) = runConversationInternal(prompt, history, imageUri, onToken, onComplete)
}
