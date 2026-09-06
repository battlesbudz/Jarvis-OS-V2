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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = ModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
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
                initialMessages = restoreTranscript(),
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onRunDirectAudioTest = { report, onFinished ->
                    runDirectAudioSmokeTest(report, onFinished)
                },
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
        return wavBytes(pcm, sampleRate)
    }

    private fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val output = ByteArrayOutputStream(44 + pcm.size)
        fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeInt(value: Int) {
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
        fun writeShort(value: Int) {
            output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }
        writeAscii("RIFF")
        writeInt(36 + pcm.size)
        writeAscii("WAVEfmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRate)
        writeInt(sampleRate * 2)
        writeShort(2)
        writeShort(16)
        writeAscii("data")
        writeInt(pcm.size)
        output.write(pcm)
        return output.toByteArray()
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
        if (modelStore.isModelOperationActive()) {
            val message = "A model operation is still finishing. Please try again in a moment."
            report(message)
            onFinished(message)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            mainHandler.post { report("Checking for the existing Gemma model…") }
            val result = modelStore.downloadOrReuse(
                spec = ModelCatalog.gemma4E2b,
                onProgress = { downloaded, total ->
                    mainHandler.post { onProgress(downloaded, total) }
                },
                onStatus = { status ->
                    mainHandler.post { report(status) }
                }
            )
            result.fold(
                onSuccess = {
                    mainHandler.post { report("Gemma found. Starting Jarvis’s final setup…") }
                    runModelSmokeTest(report, onFinished)
                },
                onFailure = { error ->
                    mainHandler.post {
                        val message = "Model setup failed: ${error.message ?: "unknown error"}"
                        report(message)
                        onFinished(message)
                    }
                }
            )
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
