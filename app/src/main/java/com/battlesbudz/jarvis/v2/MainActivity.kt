package com.battlesbudz.jarvis.v2

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.battlesbudz.jarvis.v2.actions.FunctionGemmaActionDecoder
import com.battlesbudz.jarvis.v2.actions.MobileActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
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
import java.util.Collections

class MainActivity : ComponentActivity() {
    private companion object {
        val activeConversationJobs = AtomicInteger(0)
        const val SHORT_TERM_SUMMARY_KEY = "short_term_summary"
        const val INTERRUPTED_RESPONSE = "The previous response was interrupted. Please send that again."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var modelStore: ModelStore
    private var conversationEngine: LiteRtLmEngine? = null
    private var conversationJob: Job? = null
    private var conversationCharacters = 0
    private val shortTermContext = ShortTermConversationContext()
    private lateinit var sessionPreferences: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = ModelStore(applicationContext)
        sessionPreferences = getSharedPreferences("chat_session", MODE_PRIVATE)
        shortTermContext.restoreSummary(
            savedInstanceState?.getString(SHORT_TERM_SUMMARY_KEY)
                ?: sessionPreferences.getString(SHORT_TERM_SUMMARY_KEY, null)
        )
        synchronized(diagnosticTurns) {
            diagnosticTurns.addAll(restoreDiagnostics())
        }
        setContent {
            JarvisApp(
                store = modelStore,
                initialMessages = restoreTranscript(),
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onImportModel = { uri, spec, report -> importModel(uri, spec, report) },
                onCopyDiagnostics = { transcript -> copyDiagnostics(transcript) },
                onMessagesChanged = { persistTranscript(it) },
                onSendingChanged = { sessionPreferences.edit().putBoolean("sending", it).apply() },
                onSend = { prompt, history, onToken, onComplete ->
                    runConversation(prompt, history, onToken, onComplete)
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
        sessionPreferences.edit()
            .putString(SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
            .apply()
        super.onSaveInstanceState(outState)
    }

    private val diagnosticTurns = Collections.synchronizedList(mutableListOf<String>())

    private fun restoreDiagnostics(): List<String> {
        val stored = sessionPreferences.getString("diagnostics", null).orEmpty()
        if (stored.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length())
                .map { array.getString(it) }
                .filter { it.isNotBlank() }
                .takeLast(20)
        }.getOrElse {
            // Read diagnostics written by older builds, but never use paragraph
            // boundaries to frame new entries.
            stored.split("\n\n").filter { it.isNotBlank() }.takeLast(20)
        }
    }

    private fun diagnosticsSnapshot(): String {
        val inMemory = synchronized(diagnosticTurns) {
            diagnosticTurns.takeLast(20)
        }
        return (if (inMemory.isNotEmpty()) inMemory else restoreDiagnostics())
            .joinToString("\n\n")
            .ifBlank { "No prior runtime diagnostics." }
    }

    private fun restoreTranscript(): List<ChatEntry> = runCatching {
        val json = sessionPreferences.getString("transcript", "[]") ?: "[]"
        val array = JSONArray(json)
        val restored = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ChatEntry(item.getString("role"), item.getString("text"))
        }
        if (sessionPreferences.getBoolean("sending", false)) {
            sessionPreferences.edit().putBoolean("sending", false).apply()
            restored.mapIndexed { index, entry ->
                if (index == restored.lastIndex && entry.role == "Jarvis") {
                    entry.copy(text = INTERRUPTED_RESPONSE)
                } else entry
            }
        } else restored
    }.getOrDefault(emptyList())

    private fun persistTranscript(messages: List<ChatEntry>) {
        val array = JSONArray()
        messages.takeLast(40).forEach { message ->
            array.put(JSONObject().put("role", message.role).put("text", message.text.take(4_000)))
        }
        sessionPreferences.edit().putString("transcript", array.toString()).apply()
    }

    private fun copyDiagnostics(transcript: List<ChatEntry>) {
        val visibleTranscript = transcript.joinToString("\n") { "${it.role}: ${it.text}" }
        val diagnostics = diagnosticsSnapshot()
        val bundle = """
            Jarvis OS V2 chat diagnostics
            App: ${applicationContext.packageName}
            Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
            Primary model: ${ModelCatalog.gemma4E2b.id}
            Action model: ${ModelCatalog.mobileActions270m.id}
            Session context: ${shortTermContext.diagnostics()}
            Conversation character estimate: $conversationCharacters

            Visible transcript:
            $visibleTranscript

            Recent runtime turns:
            $diagnostics
        """.trimIndent()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Jarvis diagnostics", bundle))
    }

    private fun recordDiagnostic(entry: String) {
        synchronized(diagnosticTurns) {
            diagnosticTurns.add(entry.take(6_000))
            while (diagnosticTurns.size > 20) diagnosticTurns.removeAt(0)
        }
        if (::sessionPreferences.isInitialized) {
            val persisted = synchronized(diagnosticTurns) {
                JSONArray().also { array ->
                    diagnosticTurns.takeLast(20).forEach(array::put)
                }.toString()
            }
            sessionPreferences.edit()
                .putString("diagnostics", persisted)
                .apply()
        }
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

    private fun runModelSmokeTest(report: (String) -> Unit) {
        if (!modelStore.tryBeginModelOperation()) {
            report("A model test is still finishing. Please try again in a moment.")
            return
        }
        val smokeTestJob = lifecycleScope.launch(Dispatchers.Default) {
            mainHandler.post { report("Loading local models…") }
            var primary: LiteRtLmEngine? = null
            var actions: LiteRtLmEngine? = null
            var smokeTestSucceeded = false
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    conversationCharacters = 0
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                check(modelStore.verifyIntegrity(ModelCatalog.mobileActions270m)) {
                    "The MobileActions model file changed or failed integrity verification. Re-import it."
                }
                conversationEngine?.close()
                conversationEngine = null
                primary = LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true
                )
                actions = LiteRtLmEngine(
                    ModelCatalog.mobileActions270m.id,
