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
        // About 8K tokens for typical English chat; the engine is rebuilt
        // before native KV-cache growth becomes risky on mobile.
        // LiteRT's native budget is lower in practice than Gemma's advertised
        // maximum on this phone. Rebuild before the next long turn can cross it.
        const val CONVERSATION_COMPACTION_LIMIT = 8_000
        const val GENERATION_HEADROOM = 2_500
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
                    modelStore.fileFor(ModelCatalog.mobileActions270m).path,
                    cacheDir.path,
                    useGpu = false,
                    tools = MobileActionToolDefinitions.all()
                )
                primary.initialize()
                actions.initialize()
                val primaryProbe = primary.generate(
                    "Reply with exactly GEMMA_PR1_OK and nothing else.",
                    onToken = {}
                )
                check(primaryProbe.text.trim() == "GEMMA_PR1_OK") {
                    "The selected Gemma file did not pass its identity probe."
                }
                // Setup verifies that the action model can initialize and
                // produce output. Whether a particular prompt becomes a
                // structured call is the real chat acceptance test, not a
                // brittle one-shot readiness sentinel.
                val actionProbe = actions.generate(
                    "Reply with a short confirmation that local MobileActions inference works.",
                    onToken = {}
                )
                check(actionProbe.text.isNotBlank()) {
                    "The selected MobileActions model initialized but produced no output."
                }
                smokeTestSucceeded = true
            } catch (error: Throwable) {
                mainHandler.post { report("Local model test failed: ${error.message ?: "unknown error"}") }
            } finally {
                actions?.close()
                primary?.close()
                if (smokeTestSucceeded) {
                    modelStore.markSmokeTestPassed()
                    mainHandler.post { report("Both local models initialized successfully.") }
                }
            }
        }
        smokeTestJob.invokeOnCompletion { modelStore.endModelOperation() }
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
            }
        }
    }

    private fun cleanAssistantText(text: String): String {
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

    private fun fallbackToolCall(prompt: String): com.battlesbudz.jarvis.v2.ai.ToolCall? {
        val normalized = prompt.trim()
        val openMatch = Regex(
            """(?i)^(?:can you |please )?(?:open|launch|start) (?:the )?(.+?)[?.!]*$"""
        ).find(normalized)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (appName.isNotBlank()) {
                return com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "open_app",
                    arguments = JSONObject().put("app", appName).toString()
                )
            }
        }
        val volumeCommand = Regex("""(?i)\b(set|make|turn|adjust|change)\b.*\bvolume\b""").containsMatchIn(normalized)
        if (volumeCommand) {
            val volumeValue = Regex("""(?i)\bvolume\b[^0-9]{0,20}([0-9]{1,5})(?:\s*%)?\b""").find(normalized)
                ?.groupValues?.getOrNull(1)
            if (volumeValue != null) {
                return com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "set_volume",
                    arguments = JSONObject().put("level", volumeValue).toString()
                )
            }
        }

        if (Regex("""(?i)\b(what is|check|read|show).*(my|phone|device).{0,20}\bbattery\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\bbattery\b.*\b(percent|percentage|level|left)\b""").containsMatchIn(normalized)
        ) {
            return com.battlesbudz.jarvis.v2.ai.ToolCall(
                name = "read_battery",
                arguments = JSONObject().toString()
            )
        }
        return null
    }

    private fun buildGemmaPrompt(
        userPrompt: String,
        actionResultContext: String?,
        history: List<ChatEntry>,
        seedContext: Boolean
    ): String {
        val actionContext = actionResultContext?.let { "\n\n$it" }.orEmpty()
        val sessionContext = if (seedContext) {
            shortTermContext.promptContext(history.map { it.role to it.text })
                .takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        } else ""
        return """
            You are Jarvis, a private local assistant. You have a separate
            MobileActions tool layer that can perform these validated phone
            actions: read_battery, set_volume, and open_app. FunctionGemma
            selects those actions; Kotlin validates and executes them. You
            should never claim that you have no tools. Tool calls are handled
            internally by the app. Never emit <|tool_call>, <start_function_call>,
            call:, or any other tool-call markup in your user-facing answer. If a
            tool result is included below, treat it as authoritative and explain it naturally.
            
            Current user message:
            $userPrompt
            $sessionContext
            $actionContext
        """.trimIndent()
    }

    private fun buildToolResultContext(
        userPrompt: String,
        toolName: String,
        resultMessage: String,
        succeeded: Boolean
    ): String {
        return """
            MobileActions tool execution context:
            - User request: $userPrompt
            - Selected tool: $toolName
            - Execution status: ${if (succeeded) "succeeded" else "failed"}
            - Android result: $resultMessage
        """.trimIndent()
    }

    private fun runConversation(
        prompt: String,
        history: List<ChatEntry>,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        if (!activeConversationJobs.compareAndSet(0, 1)) {
            onComplete("The previous response is still finishing. Please try again in a moment.")
            return
        }
        conversationJob = lifecycleScope.launch(Dispatchers.Default) {
            var newlyCreatedEngine: LiteRtLmEngine? = null
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                // Route natural-language action requests through FunctionGemma
                // before sending general conversation to Gemma. Tool execution
                // stays in Kotlin after validation; model output is never run
                // as arbitrary code.
                // FunctionGemma is a router, not a second chat session. If it
                // selects a tool, execute it first and carry the typed result
                // into the ongoing Gemma conversation so Gemma can explain
                // what happened and preserve conversational context.
                var actionResultForGemma: String? = null
                var actionResultMessage: String? = null
                var actionName: String? = null
                var actionEngine: LiteRtLmEngine? = null
                try {
                    actionEngine = LiteRtLmEngine(
                        ModelCatalog.mobileActions270m.id,
                        modelStore.fileFor(ModelCatalog.mobileActions270m).path,
                        cacheDir.path,
                        useGpu = false,
                        tools = MobileActionToolDefinitions.all()
                    )
                    actionEngine.initialize()
            val deterministicCall = fallbackToolCall(prompt)
            val calls = if (deterministicCall == null) {
                actionEngine.generateToolCalls(
                    "${shortTermContext.promptContext(history.map { it.role to it.text })}\n\n" +
                        "Current user request:\n$prompt"
                )
            } else emptyList()
                    val selectedCall = deterministicCall ?: calls.singleOrNull()
                    if (selectedCall != null) {
                        actionName = selectedCall.name
                        val request = FunctionGemmaActionDecoder.decode(selectedCall)
                        if (request != null) {
                            val result = MobileActionPipeline(
                                executor = AndroidMobileActionExecutor(applicationContext)
                            ).execute(request)
                            actionResultMessage = result.message
                            actionResultForGemma = buildToolResultContext(
                                userPrompt = prompt,
                                toolName = selectedCall.name,
                                resultMessage = result.message,
                                succeeded = result.succeeded
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // A normal chat request or an unavailable action backend
                    // falls through to the general Gemma conversation.
                } finally {
                    actionEngine?.close()
                }

                // Compact before the native conversation approaches its
                // practical limit. Closing the whole engine releases native
                // buffers that a conversation-only reset may retain.
                val existingPromptSize = buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = false
                ).length
                val freshPromptSize = buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = true
                ).length
                val pendingRequestSize = maxOf(existingPromptSize, freshPromptSize)
                if (conversationCharacters + pendingRequestSize + GENERATION_HEADROOM >
                    CONVERSATION_COMPACTION_LIMIT
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    conversationEngine?.close()
                    conversationEngine = null
                    conversationCharacters = 0
                }

                val engine: LiteRtLmEngine
                if (conversationEngine == null) {
                    val created = LiteRtLmEngine(
                        ModelCatalog.gemma4E2b.id,
                        modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                        cacheDir.path,
                        useGpu = true
                    )
                    newlyCreatedEngine = created
                    created.initialize()
                    conversationEngine = created
                    engine = created
                } else {
                    engine = conversationEngine!!
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                val seedContext = conversationCharacters == 0
                val generated = engine.generate(
                    prompt = buildGemmaPrompt(prompt, actionResultForGemma, history, seedContext),
                    onToken = streamFilter::accept
                )
                val rawControlOutput = generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    engine.resetConversation()
                    conversationCharacters = 0
                }
                val cleanedResponse = cleanAssistantText(generated.text)
                if (!rawControlOutput) {
                    conversationCharacters += prompt.length + generated.text.length
                }
                val previousAssistant = history.asReversed()
                    .firstOrNull { it.role == "Jarvis" }
                    ?.text?.trim()
                val repeatedFragment = cleanedResponse.length in 1..32 &&
                    cleanedResponse == previousAssistant
                if (repeatedFragment) {
                    engine.resetConversation()
                    conversationCharacters = 0
                }
                // Android's typed result is authoritative. Gemma is used to
                // explain it, but must never replace a verified success (or
                // failure) with a stale apology or hallucinated outcome.
                val finalResponse = actionResultMessage ?: if (repeatedFragment) {
                    "I lost the thread of the conversation. Please ask that again."
                } else cleanedResponse.ifBlank {
                    "I couldn't complete that phone action."
                }
                recordDiagnostic(
                    "Turn\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "historyEntries=${history.size}\n" +
                        "action=${actionName ?: "none"}\n" +
                        "actionResult=${actionResultMessage ?: "none"}\n" +
                        "generatedLength=${generated.text.length}\n" +
                        "cleaned=${cleanedResponse.take(4_000)}\n" +
                        "raw=${generated.text.take(4_000)}\n" +
                        "repeatedFragment=$repeatedFragment\n" +
                        "conversationCharacters=$conversationCharacters"
                )
                mainHandler.post { onComplete(finalResponse) }
            } catch (error: Throwable) {
                // Leave the next turn with a fresh native session after any
                // recoverable generation failure.
                conversationEngine?.close()
                conversationEngine = null
                conversationCharacters = 0
                recordDiagnostic("Turn failed\nuser=${prompt.take(1_000)}\nerror=${error.stackTraceToString().take(4_000)}")
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            } finally {
                if (newlyCreatedEngine != null && conversationEngine !== newlyCreatedEngine) {
                    newlyCreatedEngine?.close()
                }
            }
        }
        conversationJob?.invokeOnCompletion { activeConversationJobs.decrementAndGet() }
    }

}

@Composable
private fun JarvisApp(
    store: ModelStore,
    initialMessages: List<ChatEntry>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    onSend: (String, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var modelsReady by remember { mutableStateOf(store.isUsable()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
    var smokeTestRunning by remember { mutableStateOf(false) }
    var modelImportRunning by remember { mutableStateOf(store.importInProgress()) }

    // An import can finish after the previous Activity is destroyed during
    // rotation/fold changes. Keep the replacement screen synchronized with
    // the durable files even when the old callback was cancelled.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            modelsReady = store.isUsable()
            modelImportRunning = store.importInProgress()
            smokeTestPassed = modelsReady && store.smokeTestPassed()
        }
    }

    val importModel: (Uri?, com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> Unit = { uri, spec ->
        if (uri != null) {
            modelImportRunning = true
            setupStatus = "Importing local model…"
            onImportModel(uri, spec) { result ->
                modelImportRunning = false
                setupStatus = result
                if (result == "Model imported successfully.") modelsReady = store.isUsable()
            }
        }
    }
    val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.gemma4E2b)
    }
    val actionsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.mobileActions270m)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (modelsReady && smokeTestPassed) {
                JarvisChat(onSend, onCopyDiagnostics, onMessagesChanged, onSendingChanged, initialMessages)
            } else {
                ModelSetup(
                    ready = modelsReady,
                    testing = smokeTestRunning,
                    importing = modelImportRunning,
                    status = setupStatus,
                    onPickGemma = { gemmaPicker.launch(arrayOf("*/*")) },
                    onPickActions = { actionsPicker.launch(arrayOf("*/*")) },
                    onTest = {
                        smokeTestRunning = true
                        onRunModelSmokeTest.invoke { result ->
                            smokeTestRunning = false
                            setupStatus = result
                            if (result == "Both local models initialized successfully.") {
                                smokeTestPassed = true
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSetup(
    ready: Boolean,
    testing: Boolean,
    importing: Boolean,
    status: String,
    onPickGemma: () -> Unit,
    onPickActions: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Jarvis setup", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Choose the two local model files. They are stored privately on this phone.",
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(onClick = onPickGemma, modifier = Modifier.fillMaxWidth(), enabled = !testing && !importing) {
            Text("Choose Gemma 4 E2B")
        }
        Button(
            onClick = onPickActions,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = !testing && !importing
        ) {
            Text("Choose MobileActions")
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 20.dp))
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Testing local models…" else "Test local models")
        }
    }
}

private data class ChatEntry(
    val role: String,
    val text: String
)

@Composable
private fun JarvisChat(
    onSend: (String, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(initialMessages)
    }
    var isSending by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (messages.isNotEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                messages.forEach { message ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            message.role,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            message.text.ifBlank { "…" },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Message Jarvis") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onCopyDiagnostics(messages) },
            enabled = messages.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Copy diagnostics")
        }
        Button(
            onClick = {
                val submitted = prompt.trim()
                prompt = ""
                isSending = true
                val updatedMessages = messages + ChatEntry("You", submitted) + ChatEntry("Jarvis", "")
                messages = updatedMessages
                onMessagesChanged(updatedMessages)
                onSendingChanged(true)
                onSend(
                    submitted,
                    messages.dropLast(2),
                    { token ->
                        messages = messages.dropLast(1) +
                            ChatEntry("Jarvis", messages.lastOrNull()?.text.orEmpty() + token)
                    },
                    { result ->
                        messages = messages.dropLast(1) + ChatEntry("Jarvis", result)
                        onMessagesChanged(messages)
                        onSendingChanged(false)
                        isSending = false
                    }
                )
            },
            enabled = prompt.isNotBlank() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(if (isSending) "Thinking…" else "Send")
        }
    }
}
