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
        // This is an app-side character budget, not Gemma's advertised
        // context maximum. It leaves room for a normal long answer while
        // rebuilding the native conversation before retained chat grows too far
        // for this device/runtime.
        const val CONVERSATION_COMPACTION_LIMIT = 10_000
        const val GENERATION_HEADROOM = 2_000
        const val MAX_USER_PROMPT_CHARS = 12_000
        const val INTERRUPTED_RESPONSE = "The previous response was interrupted. Please send that again."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var modelStore: ModelStore
    private var conversationEngine: LiteRtLmEngine? = null
    private var conversationJob: Job? = null
    private var conversationCharacters = 0
    private val shortTermContext = ShortTermConversationContext()
    private val referenceGrounding = ReferenceGroundingClient()
    private val factualityVerifier = com.battlesbudz.jarvis.v2.ai.FactualityVerifier()
    private val turnOrchestrator = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(referenceGrounding)
    private val promptBuilder = com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder(shortTermContext)
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
                onSend = { prompt, imageUri, history, onToken, onComplete ->
                    runConversation(prompt, history, imageUri, onToken, onComplete)
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
            Action model: Gemma-native structured tools
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
            mainHandler.post { report("Loading Gemma 4 E2B…") }
            var gemma: LiteRtLmEngine? = null
            var smokeTestSucceeded = false
            try {
                check(modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    "The Gemma model file changed or failed integrity verification. Re-import it."
                }
                conversationEngine?.close()
                conversationEngine = null
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
                mainHandler.post { report("Gemma model test failed: ${error.message ?: "unknown error"}") }
            } finally {
                gemma?.close()
                if (smokeTestSucceeded) {
                    modelStore.markSmokeTestPassed()
                    mainHandler.post { report("Gemma 4 E2B initialized successfully.") }
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

    private fun classifyActionIntent(
        prompt: String,
        history: List<ChatEntry>
    ): com.battlesbudz.jarvis.v2.ai.ToolCall? {
        val normalized = prompt.trim()

        fun openApp(appName: String): com.battlesbudz.jarvis.v2.ai.ToolCall? {
            val cleanedName = appName.trim().trimEnd('.', '?', '!', ',')
            return cleanedName.takeIf {
                it.isNotBlank() && !it.equals("it", ignoreCase = true) &&
                    !it.equals("that", ignoreCase = true)
            }?.let {
                com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "open_app",
                    arguments = JSONObject().put("app", it).toString()
                )
            }
        }

        // Classify the request semantically enough for the small on-device
        // action set: an opening verb plus an app target means open_app,
        // regardless of where those words occur in the sentence.
        val openingVerb = Regex("""(?i)\b(open|launch|start)\b""").containsMatchIn(normalized)
        if (openingVerb) {
            val appMatch = Regex(
                """(?i)\b(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s+(?:for me|please|right now)|\s*[?.!,]|$)"""
            ).find(normalized)
            if (appMatch != null) return openApp(appMatch.groupValues[1])
        }

        // Resolve a pronoun or generic confirmation only when a recent turn
        // established a specific app-opening request or offer.
        val genericConfirmation = Regex(
            """(?i)^(?:(?:okay|ok|yes|sure)(?:\s+(?:thanks|thank you|can you|could you|please|do it|now|and))*|do it|go ahead|open it|open that)(?:\s+please)?[?.!]*$"""
        ).matches(normalized)
        if (genericConfirmation) {
            val priorApp = history.asReversed().asSequence()
                .mapNotNull { entry ->
                    val text = entry.text.trim()
                    val explicit = Regex(
                        """(?i)\b(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s+(?:for me|please|right now)|\s*[?.!,]|$)"""
                    ).find(text)?.groupValues?.getOrNull(1)
                    val offered = Regex(
                        """(?i)\b(?:can|could|will|shall)\s+(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s*[?.!,]|$)"""
                    ).find(text)?.groupValues?.getOrNull(1)
                    explicit ?: offered
                }
                .firstOrNull { !it.equals("it", ignoreCase = true) && !it.equals("that", ignoreCase = true) }
            if (priorApp != null) return openApp(priorApp)
        }

        val volumeCommand = Regex("""(?i)\b(set|make|turn|adjust|change|raise|lower|increase|decrease)\b.*\bvolume\b""")
            .containsMatchIn(normalized)
        if (volumeCommand) {
            val volumeValue = Regex("""(?i)\bvolume\b[^0-9]{0,20}([0-9]{1,5})(?:\s*%)?\b""")
                .find(normalized)?.groupValues?.getOrNull(1)
            if (volumeValue != null) {
                return com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "set_volume",
                    arguments = JSONObject().put("level", volumeValue).toString()
                )
            }
        }

        if (
            Regex("""(?i)\b(what(?:'s| is| does)|how much|check|read|show)\b.{0,30}\bbattery\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b(my|phone|device)'?s?\s+battery\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\bbattery\b.{0,30}\b(say|percent|percentage|level|left|status|remaining)\b""").containsMatchIn(normalized)
        ) {
            return com.battlesbudz.jarvis.v2.ai.ToolCall(
                name = "read_battery",
                arguments = JSONObject().toString()
            )
        }
        return null
    }


    private fun toolMatchesUserIntent(
        prompt: String,
        history: List<ChatEntry>,
        call: com.battlesbudz.jarvis.v2.ai.ToolCall
    ): Boolean {
        val normalized = prompt.trim()
        return when (call.name.lowercase()) {
            "read_battery" -> classifyActionIntent(normalized, history)?.name == "read_battery"
            "set_volume" -> classifyActionIntent(normalized, history)?.name == "set_volume"
            "open_app" -> classifyActionIntent(normalized, history)?.name == "open_app"
            else -> false
        }
    }

    private fun runConversation(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
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
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > MAX_USER_PROMPT_CHARS) {
                    recordDiagnostic(
                        "Turn rejected before action routing\\n" +
                            "userLength=${prompt.length}\\n" +
                            "reason=single user message exceeds safe mobile budget"
                    )
                    mainHandler.post {
                        onComplete(
                            "That request is too large for the local model's safe mobile budget. " +
                                "Please send it in smaller parts."
                        )
                    }
                    return@launch
                }

                var actionResultForGemma: String? = null
                var actionResultMessage: String? = null
                var actionName: String? = null
                val turnPlan = turnOrchestrator.plan(prompt)
                val referenceContext = turnPlan.lookupQuery?.let {
                    referenceGrounding.fetchIfRequested(it)?.context
                }

                // Include retrieved evidence in the budget calculation. A
                // factual lookup must trigger compaction before the fresh prompt
                // is submitted, rather than being rejected after construction.
                val referenceSize = referenceContext?.length ?: 0

                // Compact before the native conversation approaches its
                // practical limit. Closing the whole engine releases native
                // buffers that a conversation-only reset may retain.
                val existingPromptSize = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = false
                ).length
                val freshPromptSize = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = true
                ).length
                val pendingRequestSize = maxOf(existingPromptSize, freshPromptSize) + referenceSize
                var promptHistory = history
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
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    conversationEngine?.close()
                    conversationEngine = null
                    conversationCharacters = 0
                }

                // Fully recreate the native runtime for each turn. LiteRT's
                // Conversation reset is not sufficient on this device because the
                // Engine may retain GPU/context buffers after the conversation closes.
                conversationEngine?.close()
                conversationEngine = null
                val engine = LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    visionEnabled = true
                )
                newlyCreatedEngine = engine
                engine.initialize()
                conversationEngine = engine
                conversationCharacters = 0
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                val seedContext = true
                var submittedPrompt = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    promptHistory,
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + GENERATION_HEADROOM >
                    CONVERSATION_COMPACTION_LIMIT
                ) {
                    conversationEngine?.close()
                    conversationEngine = null
                    conversationCharacters = 0
                    recordDiagnostic(
                        "Turn rejected\\n" +
                            "userLength=${prompt.length}\\n" +
                            "submittedPromptLength=${submittedPrompt.length}\\n" +
                            "reason=prompt exceeds fresh-session context budget"
                    )
                    mainHandler.post {
                        onComplete(
                            "That request is too large for the local model's safe mobile budget. " +
                                "Please send it in smaller parts."
                        )
                    }
                    return@launch
                }
                val imageBytes = imageUri?.let { uri ->
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().also { bytes ->
                            check(bytes.size <= MAX_IMAGE_BYTES) {
                                "The selected image is too large for safe local inference."
                            }
                        }
                    } ?: error("The selected image could not be read.")
                }
                var generated = if (imageBytes != null) {
                    engine.generate(
                        prompt = submittedPrompt,
                        imageBytes = imageBytes,
                        onToken = streamFilter::accept
                    )
                } else {
                    engine.generate(
                        prompt = submittedPrompt,
                        onToken = streamFilter::accept
                    )
                }
                val candidateCall = generated.toolCalls.singleOrNull()
                // Gemma can occasionally emit a tool call copied from the
                // previous turn while answering a normal question. Never let
                // that stale call cause a phone side effect.
                val proposedCall = candidateCall?.takeIf {
                    toolMatchesUserIntent(prompt, history, it)
                }
                if (proposedCall != null &&
                    proposedCall.name in setOf("read_battery", "set_volume", "open_app")
                ) {
                    actionName = proposedCall.name
                    val request = com.battlesbudz.jarvis.v2.actions.FunctionGemmaActionDecoder.decode(proposedCall)
                    if (request != null) {
                        val result = com.battlesbudz.jarvis.v2.actions.MobileActionPipeline(
                            executor = com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor(applicationContext)
                        ).execute(request)
                        actionResultMessage = result.message
                        actionResultForGemma = promptBuilder.buildToolResultContext(
                            userPrompt = prompt,
                            toolName = proposedCall.name,
                            resultMessage = result.message,
                            succeeded = result.succeeded
                        )
                        generated = engine.sendToolResult(
                            proposedCall,
                            actionResultForGemma!!,
                            streamFilter::accept
                        )
                    }
                }
                // A rejected tool call can sometimes contain no answer text at all.
                // Retry that turn as ordinary conversation so a normal question
                // never falls through to a phone-action error message.
                if (candidateCall != null &&
                    proposedCall == null &&
                    cleanAssistantText(generated.text).isBlank()
                ) {
                    engine.resetConversation()
                    conversationCharacters = 0
                    val retryPrompt = submittedPrompt + """
                        
                        The previous output contained an invalid tool call. Answer the user's current message directly as normal text. Do not call a tool.
                    """.trimIndent()
                    generated = if (imageBytes != null) {
                        engine.generate(
                            prompt = retryPrompt,
                            imageBytes = imageBytes,
                            onToken = streamFilter::accept
                        )
                    } else {
                        engine.generate(
                            prompt = retryPrompt,
                            onToken = streamFilter::accept
                        )
                    }
                }
                val localAnswer = cleanAssistantText(generated.text)
                val isFactualQuestion =
                    referenceContext == null &&
                        actionName == null &&
                        turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST
                val verifierRequestsLookup =
                    isFactualQuestion &&
                        !referenceGrounding.isInsufficientAnswer(localAnswer) &&
                        run {
                            // This pass is internal and never reaches the user.
                            // It catches confident-looking entity or historical
                            // errors that phrase matching cannot detect.
                            engine.resetConversation()
                            conversationCharacters = 0
                            val verdict = engine.generate(
                                prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                                onToken = {}
                            )
                            factualityVerifier.requestsLookup(verdict.text)
                        }
                val shouldUseAutomaticFallback =
                    isFactualQuestion &&
                        (referenceGrounding.isInsufficientAnswer(localAnswer) ||
                            verifierRequestsLookup)
                if (shouldUseAutomaticFallback) {
                    val fallbackQuery = turnOrchestrator.automaticFallbackQuery(prompt)
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    if (!fallbackContext.isNullOrBlank()) {
                        engine.resetConversation()
                        conversationCharacters = 0
                        val fallbackPrompt = promptBuilder.buildGemmaPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext
                        ) + "\n\n" + fallbackContext
                        generated = if (imageBytes != null) {
                            engine.generate(
                                prompt = fallbackPrompt,
                                imageBytes = imageBytes,
                                onToken = streamFilter::accept
                            )
                        } else {
                            engine.generate(
                                prompt = fallbackPrompt,
                                onToken = streamFilter::accept
                            )
                        }
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    engine.resetConversation()
                    conversationCharacters = 0
                }
                val cleanedResponse = cleanAssistantText(generated.text)
                if (!rawControlOutput) {
                    // Count the exact prompt submitted to the native engine,
                    // including Jarvis instructions and injected tool/context data.
                    conversationCharacters += submittedPrompt.length + generated.text.length
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
                    if (actionName != null) {
                        "I couldn't complete that phone action."
                    } else {
                        "I couldn't generate a response. Please try that again."
                    }
                }
                turnOrchestrator.recordResponse(prompt, finalResponse, turnPlan)
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
                // Do not retain native LiteRT/GPU buffers between turns.
                // The next request receives its bounded context capsule when
                // it creates a new engine.
                if (newlyCreatedEngine != null) {
                    if (conversationEngine === newlyCreatedEngine) {
                        conversationEngine = null
                    }
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
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
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

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
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
                    onTest = {
                        smokeTestRunning = true
                        onRunModelSmokeTest.invoke { result ->
                            smokeTestRunning = false
                            setupStatus = result
                            if (result == "Gemma 4 E2B initialized successfully.") {
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
            "Choose the Gemma 4 E2B local model file. Phone actions run through Gemma's built-in structured tools.",
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(onClick = onPickGemma, modifier = Modifier.fillMaxWidth(), enabled = !testing && !importing) {
            Text("Choose Gemma 4 E2B")
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 20.dp))
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Testing Gemma 4 E2B…" else "Test Gemma 4 E2B")
        }
    }
}

private const val MAX_SAVED_DRAFT_CHARS = 16_000
private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

data class ChatEntry(
    val role: String,
    val text: String
)

@Composable
private fun JarvisChat(
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(initialMessages) }
    var isSending by remember { mutableStateOf(false) }
    var attachedImageName by rememberSaveable { mutableStateOf<String?>(null) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    val transcriptScrollState = rememberScrollState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachedImageUri = uri
        attachedImageName = uri?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: if (uri != null) "selected image" else null
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (messages.isNotEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(transcriptScrollState).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                messages.forEach { message ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = if (message.role == "You") {
                            androidx.compose.ui.Alignment.End
                        } else {
                            androidx.compose.ui.Alignment.Start
                        }
                    ) {
                        Text(
                            message.role,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (message.role == "You") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        Surface(
                            color = if (message.role == "You") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                message.text.ifBlank { "…" },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = if (message.role == "You") {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
        if (attachedImageName != null) {
            Text(
                "Attached: $attachedImageName",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.Bottom
        ) {
            Button(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isSending,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("📎")
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(MAX_SAVED_DRAFT_CHARS) },
                label = { Text("Message Jarvis") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = { onCopyDiagnostics(messages) },
            enabled = messages.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Copy diagnostics")
        }
        Button(
            onClick = {
                val selectedImageUri = attachedImageUri
                val submitted = buildString {
                    append(prompt.trim())
                    attachedImageName?.let {
                        append("\n\n[Attached image: $it]")
                    }
                }.trim()
                prompt = ""
                attachedImageUri = null
                attachedImageName = null
                isSending = true
                val updatedMessages = messages + ChatEntry("You", submitted) + ChatEntry("Jarvis", "")
                messages = updatedMessages
                onMessagesChanged(updatedMessages)
                onSendingChanged(true)
                onSend(
                    submitted,
                    selectedImageUri,
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
