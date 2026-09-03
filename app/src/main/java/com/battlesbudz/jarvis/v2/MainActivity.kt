package com.battlesbudz.jarvis.v2

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private companion object {
        val activeConversationJobs = AtomicInteger(0)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var modelStore: ModelStore
    private var conversationEngine: LiteRtLmEngine? = null
    private var conversationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelStore = ModelStore(applicationContext)
        setContent {
            JarvisApp(
                store = modelStore,
                onRunModelSmokeTest = { runModelSmokeTest(it) },
                onImportModel = { uri, spec, report -> importModel(uri, spec, report) },
                onSend = { prompt, onToken, onComplete ->
                    runConversation(prompt, onToken, onComplete)
                }
            )
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
                Regex("""(?s)<\|tool_call>.*?<\|tool_call\|>"""),
                ""
            )
            .replace(
                Regex("""(?s)<start_function_call>.*?<end_function_call>"""),
                ""
            )
            .trim()
        return cleaned.ifBlank { "I couldn't complete that phone action." }
    }

    private fun buildGemmaPrompt(
        userPrompt: String,
        actionResultContext: String?
    ): String {
        val actionContext = actionResultContext?.let { "\n\n$it" }.orEmpty()
        return """
            You are Jarvis, a private local assistant. You have a separate
            MobileActions tool layer that can perform these validated phone
            actions: read_battery, set_volume, and open_app. FunctionGemma
            selects those actions; Kotlin validates and executes them. You
            should never claim that you have no tools. Tool calls are handled
            internally by the app. Never emit <|tool_call>, <start_function_call>,
            call:, or any other tool-call markup in your user-facing answer. If a
            tool result is included below, treat it as authoritative and explain it naturally.
            
            User message:
            $userPrompt
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
                    val calls = actionEngine.generateToolCalls(prompt)
                    if (calls.size == 1) {
                        val request = FunctionGemmaActionDecoder.decode(calls.single())
                        if (request != null) {
                            val result = MobileActionPipeline(
                                executor = AndroidMobileActionExecutor(applicationContext)
                            ).execute(request)
                            actionResultForGemma = buildToolResultContext(
                                userPrompt = prompt,
                                toolName = calls.single().name,
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
                // Hold the assistant output until generation completes. This keeps
                // partially emitted model control tokens from ever becoming visible
                // chat text; the transcript is still updated as one complete message.
                val generated = engine.generate(
                    prompt = buildGemmaPrompt(prompt, actionResultForGemma),
                    onToken = {}
                )
                mainHandler.post { onComplete(cleanAssistantText(generated.text)) }
            } catch (error: Throwable) {
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
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onSend: (String, (String) -> Unit, (String) -> Unit) -> Unit
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
                JarvisChat(onSend)
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
    onSend: (String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(emptyList<ChatEntry>()) }
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
            onClick = {
                val submitted = prompt.trim()
                prompt = ""
                isSending = true
                messages = messages + ChatEntry("You", submitted) + ChatEntry("Jarvis", "")
                onSend(
                    submitted,
                    { token ->
                        messages = messages.dropLast(1) +
                            ChatEntry("Jarvis", messages.lastOrNull()?.text.orEmpty() + token)
                    },
                    { result ->
                        messages = messages.dropLast(1) + ChatEntry("Jarvis", result)
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
