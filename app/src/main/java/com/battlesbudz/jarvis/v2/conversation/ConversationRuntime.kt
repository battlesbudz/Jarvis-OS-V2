package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

internal fun MainActivity.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unitpackage com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

internal fun MainActivity.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        if (!MainActivity.activeConversationJobs.compareAndSet(0, 1)) {
            onComplete("The previous response is still finishing. Please try again in a moment.")
            return
        }
        conversationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > MainActivity.MAX_USER_PROMPT_CHARS) {
                    diagnosticRecorder.record(
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

                // Automatic factual routing owns the lookup decision. If the
                // reference service is unavailable, do not let the local model
                // bounce the same question back to the user as an offer to
                // search; report the failed automatic attempt directly.
                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Automatic factual lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
                    )
                    mainHandler.post {
                        onComplete("I tried to verify that with Wikipedia, but it was unavailable right now.")
                    }
                    return@launch
                }

                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.EXPLICIT_LOOKUP &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Turn lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}\\n" +
                            "reason=reference source returned no evidence"
                    )
                    mainHandler.post {
                        onComplete(
                            "I couldn't reach Wikipedia right now. Please check your connection and try again."
                        )
                    }
                    return@launch
                }

                // Include retrieved evidence in the budget calculation. A
                // factual lookup must trigger compaction before the fresh prompt
                // is submitted, rather than being rejected after construction.
                val referenceSize = referenceContext?.length ?: 0

                // Compact before the native conversation approaches its
                // practical limit. Keep the transcript in the app and reset
                // only the bounded native conversation.
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
                if (conversationCharacters + pendingRequestSize + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(MainActivity.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    resetNativeConversation()
                }

                // Keep the expensive model/GPU engine alive. The replaceable
                // Conversation is reset only when the bounded context needs
                // to be compacted or an isolated retry is required.
                val engine = conversationEngine ?: LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    visionEnabled = true
                ).also {
                    it.initialize()
                    conversationEngine = it
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                var seedContext = !nativeConversationHasContext
                var submittedPrompt = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    if (seedContext) promptHistory else emptyList(),
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // A compacted summary is useful background, but it must
                    // never crowd out the current request or retrieved image /
                    // Wikipedia evidence. Retry the fresh session without
                    // seeded history before rejecting the user turn.
                    seedContext = false
                    submittedPrompt = promptBuilder.buildGemmaPrompt(
                        prompt,
                        actionResultForGemma,
                        emptyList(),
                        seedContext = false
                    )
                    turnPlan.activeSubject?.let {
                        submittedPrompt += "\n\nResolved subject for this turn: " + it
                    }
                    submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                }
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // Do not reject a valid user turn just because retained
                    // context is large. The prompt builder already removed
                    // stale history; let the model answer as concisely as it
                    // can and retain the diagnostic for later tuning.
                    diagnosticRecorder.record(
                        "Turn context remains near budget\\n" +
                            "userLength=${prompt.length}\\n" +
                            "submittedPromptLength=${submittedPrompt.length}\\n" +
                            "action=continue with concise response"
                    )
                }
                val imageBytes = imageUri?.let { uri ->
                    openVisionInputStream(uri)?.use { input ->
                        input.readBytes().also { bytes ->
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
                var nativeConversationContainsCurrentTurn = true
                val candidateCall = generated.toolCalls.singleOrNull()
                // Gemma can occasionally emit a tool call copied from the
                // previous turn while answering a normal question. Never let
                // that stale call cause a phone side effect.
                val proposedCall = candidateCall?.takeIf {
                    actionIntentRouter.toolMatchesUserIntent(prompt, history, it)
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
                    resetNativeConversation()
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
                var verifierRequestsLookup = false
                if (isFactualQuestion &&
                    !referenceGrounding.isInsufficientAnswer(localAnswer)
                ) {
                    // This pass is internal and never reaches the user. It
                    // catches confident-looking entity or historical errors
                    // that phrase matching cannot detect.
                    resetNativeConversation()
                    val verdict = engine.generate(
                        prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                        onToken = {}
                    )
                    verifierRequestsLookup = factualityVerifier.requestsLookup(verdict.text)
                    // The verifier is an isolated internal pass. Do not leave
                    // its prompt in the user conversation.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
                }
                val shouldUseAutomaticFallback =
                    isFactualQuestion &&
                        (referenceGrounding.isInsufficientAnswer(localAnswer) ||
                            verifierRequestsLookup)
                if (shouldUseAutomaticFallback) {
                    val fallbackQuery = turnOrchestrator.automaticFallbackQuery(prompt)
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = promptBuilder.buildGemmaPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
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
                        nativeConversationContainsCurrentTurn = true
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                nativeConversationHasContext = nativeConversationContainsCurrentTurn
                diagnosticRecorder.record(
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
                nativeConversationHasContext = false
                conversationCharacters = 0
                diagnosticRecorder.record(
                    "Turn failed\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "imageAttached=${imageUri != null}\n" +
                        "error=${error.stackTraceToString().take(4_000)}"
                )
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            }
        }
        conversationJob?.invokeOnCompletion { MainActivity.activeConversationJobs.decrementAndGet() }
    }

private fun MainActivity.openVisionInputStream(uri: Uri): InputStream? {
    return runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}
    ) {
        if (!MainActivity.activeConversationJobs.compareAndSet(0, 1)) {
            onComplete("The previous response is still finishing. Please try again in a moment.")
            return
        }
        conversationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > MainActivity.MAX_USER_PROMPT_CHARS) {
                    diagnosticRecorder.record(
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

                // Automatic factual routing owns the lookup decision. If the
                // reference service is unavailable, do not let the local model
                // bounce the same question back to the user as an offer to
                // search; report the failed automatic attempt directly.
                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Automatic factual lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
                    )
                    mainHandler.post {
                        onComplete("I tried to verify that with Wikipedia, but it was unavailable right now.")
                    }
                    return@launch
                }

                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.EXPLICIT_LOOKUP &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Turn lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}\\n" +
                            "reason=reference source returned no evidence"
                    )
                    mainHandler.post {
                        onComplete(
                            "I couldn't reach Wikipedia right now. Please check your connection and try again."
                        )
                    }
                    return@launch
                }

                // Include retrieved evidence in the budget calculation. A
                // factual lookup must trigger compaction before the fresh prompt
                // is submitted, rather than being rejected after construction.
                val referenceSize = referenceContext?.length ?: 0

                // Compact before the native conversation approaches its
                // practical limit. Keep the transcript in the app and reset
                // only the bounded native conversation.
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
                if (conversationCharacters + pendingRequestSize + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(MainActivity.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    resetNativeConversation()
                }

                // Keep the expensive model/GPU engine alive. The replaceable
                // Conversation is reset only when the bounded context needs
                // to be compacted or an isolated retry is required.
                val engine = conversationEngine ?: LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    visionEnabled = true
                ).also {
                    it.initialize()
                    conversationEngine = it
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                var seedContext = !nativeConversationHasContext
                var submittedPrompt = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    if (seedContext) promptHistory else emptyList(),
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // A compacted summary is useful background, but it must
                    // never crowd out the current request or retrieved image /
                    // Wikipedia evidence. Retry the fresh session without
                    // seeded history before rejecting the user turn.
                    seedContext = false
                    submittedPrompt = promptBuilder.buildGemmaPrompt(
                        prompt,
                        actionResultForGemma,
                        emptyList(),
                        seedContext = false
                    )
                    turnPlan.activeSubject?.let {
                        submittedPrompt += "\n\nResolved subject for this turn: " + it
                    }
                    submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                }
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // Do not reject a valid user turn just because retained
                    // context is large. The prompt builder already removed
                    // stale history; let the model answer as concisely as it
                    // can and retain the diagnostic for later tuning.
                    diagnosticRecorder.record(
                        "Turn context remains near budget\\n" +
                            "userLength=${prompt.length}\\n" +
                            "submittedPromptLength=${submittedPrompt.length}\\n" +
                            "action=continue with concise response"
                    )
                }
                val imageBytes = imageUri?.let { uri ->
                    openVisionInputStream(uri)?.use { input ->
                        input.readBytes().also { bytes ->
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
                var nativeConversationContainsCurrentTurn = true
                val candidateCall = generated.toolCalls.singleOrNull()
                // Gemma can occasionally emit a tool call copied from the
                // previous turn while answering a normal question. Never let
                // that stale call cause a phone side effect.
                val proposedCall = candidateCall?.takeIf {
                    actionIntentRouter.toolMatchesUserIntent(prompt, history, it)
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
                    resetNativeConversation()
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
                var verifierRequestsLookup = false
                if (isFactualQuestion &&
                    !referenceGrounding.isInsufficientAnswer(localAnswer)
                ) {
                    // This pass is internal and never reaches the user. It
                    // catches confident-looking entity or historical errors
                    // that phrase matching cannot detect.
                    resetNativeConversation()
                    val verdict = engine.generate(
                        prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                        onToken = {}
                    )
                    verifierRequestsLookup = factualityVerifier.requestsLookup(verdict.text)
                    // The verifier is an isolated internal pass. Do not leave
                    // its prompt in the user conversation.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
                }
                val shouldUseAutomaticFallback =
                    isFactualQuestion &&
                        (referenceGrounding.isInsufficientAnswer(localAnswer) ||
                            verifierRequestsLookup)
                if (shouldUseAutomaticFallback) {
                    val fallbackQuery = turnOrchestrator.automaticFallbackQuery(prompt)
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = promptBuilder.buildGemmaPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
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
                        nativeConversationContainsCurrentTurn = true
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                nativeConversationHasContext = nativeConversationContainsCurrentTurn
                diagnosticRecorder.record(
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
                nativeConversationHasContext = false
                conversationCharacters = 0
                diagnosticRecorder.record(
                    "Turn failed\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "imageAttached=${imageUri != null}\n" +
                        "error=${error.stackTraceToString().take(4_000)}"
                )
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            }
        }
        conversationJob?.invokeOnCompletion { MainActivity.activeConversationJobs.decrementAndGet() }
    }

private fun MainActivity.openVisionInputStream(uri: Uri): InputStream? {
    return runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}
package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

internal fun MainActivity.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        if (!MainActivity.activeConversationJobs.compareAndSet(0, 1)) {
            onComplete("The previous response is still finishing. Please try again in a moment.")
            return
        }
        conversationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > MainActivity.MAX_USER_PROMPT_CHARS) {
                    diagnosticRecorder.record(
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

                // Automatic factual routing owns the lookup decision. If the
                // reference service is unavailable, do not let the local model
                // bounce the same question back to the user as an offer to
                // search; report the failed automatic attempt directly.
                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Automatic factual lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}"
                    )
                    mainHandler.post {
                        onComplete("I tried to verify that with Wikipedia, but it was unavailable right now.")
                    }
                    return@launch
                }

                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.EXPLICIT_LOOKUP &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Turn lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}\\n" +
                            "reason=reference source returned no evidence"
                    )
                    mainHandler.post {
                        onComplete(
                            "I couldn't reach Wikipedia right now. Please check your connection and try again."
                        )
                    }
                    return@launch
                }

                // Include retrieved evidence in the budget calculation. A
                // factual lookup must trigger compaction before the fresh prompt
                // is submitted, rather than being rejected after construction.
                val referenceSize = referenceContext?.length ?: 0

                // Compact before the native conversation approaches its
                // practical limit. Keep the transcript in the app and reset
                // only the bounded native conversation.
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
                if (conversationCharacters + pendingRequestSize + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(MainActivity.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    resetNativeConversation()
                }

                // Keep the expensive model/GPU engine alive. The replaceable
                // Conversation is reset only when the bounded context needs
                // to be compacted or an isolated retry is required.
                val engine = conversationEngine ?: LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    visionEnabled = true
                ).also {
                    it.initialize()
                    conversationEngine = it
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                var seedContext = !nativeConversationHasContext
                var submittedPrompt = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    if (seedContext) promptHistory else emptyList(),
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // A compacted summary is useful background, but it must
                    // never crowd out the current request or retrieved image /
                    // Wikipedia evidence. Retry the fresh session without
                    // seeded history before rejecting the user turn.
                    seedContext = false
                    submittedPrompt = promptBuilder.buildGemmaPrompt(
                        prompt,
                        actionResultForGemma,
                        emptyList(),
                        seedContext = false
                    )
                    turnPlan.activeSubject?.let {
                        submittedPrompt += "\n\nResolved subject for this turn: " + it
                    }
                    submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                }
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // Do not reject a valid user turn just because retained
                    // context is large. The prompt builder already removed
                    // stale history; let the model answer as concisely as it
                    // can and retain the diagnostic for later tuning.
                    diagnosticRecorder.record(
                        "Turn context remains near budget\\n" +
                            "userLength=${prompt.length}\\n" +
                            "submittedPromptLength=${submittedPrompt.length}\\n" +
                            "action=continue with concise response"
                    )
                }
                val imageBytes = imageUri?.let { uri ->
                    openVisionInputStream(uri)?.use { input ->
                        input.readBytes().also { bytes ->
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
                var nativeConversationContainsCurrentTurn = true
                val candidateCall = generated.toolCalls.singleOrNull()
                // Gemma can occasionally emit a tool call copied from the
                // previous turn while answering a normal question. Never let
                // that stale call cause a phone side effect.
                val proposedCall = candidateCall?.takeIf {
                    actionIntentRouter.toolMatchesUserIntent(prompt, history, it)
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
                    resetNativeConversation()
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
                var verifierRequestsLookup = false
                if (isFactualQuestion &&
                    !referenceGrounding.isInsufficientAnswer(localAnswer)
                ) {
                    // This pass is internal and never reaches the user. It
                    // catches confident-looking entity or historical errors
                    // that phrase matching cannot detect.
                    resetNativeConversation()
                    val verdict = engine.generate(
                        prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                        onToken = {}
                    )
                    verifierRequestsLookup = factualityVerifier.requestsLookup(verdict.text)
                    // The verifier is an isolated internal pass. Do not leave
                    // its prompt in the user conversation.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
                }
                val shouldUseAutomaticFallback =
                    isFactualQuestion &&
                        (referenceGrounding.isInsufficientAnswer(localAnswer) ||
                            verifierRequestsLookup)
                if (shouldUseAutomaticFallback) {
                    val fallbackQuery = turnOrchestrator.automaticFallbackQuery(prompt)
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = promptBuilder.buildGemmaPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
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
                        nativeConversationContainsCurrentTurn = true
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                nativeConversationHasContext = nativeConversationContainsCurrentTurn
                diagnosticRecorder.record(
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
                nativeConversationHasContext = false
                conversationCharacters = 0
                diagnosticRecorder.record(
                    "Turn failed\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "imageAttached=${imageUri != null}\n" +
                        "error=${error.stackTraceToString().take(4_000)}"
                )
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            }
        }
        conversationJob?.invokeOnCompletion { MainActivity.activeConversationJobs.decrementAndGet() }
    }

private fun MainActivity.openVisionInputStream(uri: Uri): InputStream? {
    return runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}
package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream

internal fun MainActivity.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        if (!MainActivity.activeConversationJobs.compareAndSet(0, 1)) {
            onComplete("The previous response is still finishing. Please try again in a moment.")
            return
        }
        conversationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                if (!modelStore.verifyIntegrity(ModelCatalog.gemma4E2b)) {
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The Gemma model file changed or failed integrity verification. Re-import it.")
                }
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > MainActivity.MAX_USER_PROMPT_CHARS) {
                    diagnosticRecorder.record(
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

                if (turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.EXPLICIT_LOOKUP &&
                    referenceContext.isNullOrBlank()
                ) {
                    diagnosticRecorder.record(
                        "Turn lookup failed\\n" +
                            "user=${prompt.take(1_000)}\\n" +
                            "lookupQuery=${turnPlan.lookupQuery?.take(1_000)}\\n" +
                            "reason=reference source returned no evidence"
                    )
                    mainHandler.post {
                        onComplete(
                            "I couldn't reach Wikipedia right now. Please check your connection and try again."
                        )
                    }
                    return@launch
                }

                // Include retrieved evidence in the budget calculation. A
                // factual lookup must trigger compaction before the fresh prompt
                // is submitted, rather than being rejected after construction.
                val referenceSize = referenceContext?.length ?: 0

                // Compact before the native conversation approaches its
                // practical limit. Keep the transcript in the app and reset
                // only the bounded native conversation.
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
                if (conversationCharacters + pendingRequestSize + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(MainActivity.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    resetNativeConversation()
                }

                // Keep the expensive model/GPU engine alive. The replaceable
                // Conversation is reset only when the bounded context needs
                // to be compacted or an isolated retry is required.
                val engine = conversationEngine ?: LiteRtLmEngine(
                    ModelCatalog.gemma4E2b.id,
                    modelStore.fileFor(ModelCatalog.gemma4E2b).path,
                    cacheDir.path,
                    useGpu = true,
                    tools = com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all(),
                    visionEnabled = true
                ).also {
                    it.initialize()
                    conversationEngine = it
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    mainHandler.post { onToken(safeText) }
                }
                var seedContext = !nativeConversationHasContext
                var submittedPrompt = promptBuilder.buildGemmaPrompt(
                    prompt,
                    actionResultForGemma,
                    if (seedContext) promptHistory else emptyList(),
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // A compacted summary is useful background, but it must
                    // never crowd out the current request or retrieved image /
                    // Wikipedia evidence. Retry the fresh session without
                    // seeded history before rejecting the user turn.
                    seedContext = false
                    submittedPrompt = promptBuilder.buildGemmaPrompt(
                        prompt,
                        actionResultForGemma,
                        emptyList(),
                        seedContext = false
                    )
                    turnPlan.activeSubject?.let {
                        submittedPrompt += "\n\nResolved subject for this turn: " + it
                    }
                    submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                }
                if (submittedPrompt.length + MainActivity.GENERATION_HEADROOM >
                    MainActivity.CONVERSATION_COMPACTION_LIMIT
                ) {
                    // Do not reject a valid user turn just because retained
                    // context is large. The prompt builder already removed
                    // stale history; let the model answer as concisely as it
                    // can and retain the diagnostic for later tuning.
                    diagnosticRecorder.record(
                        "Turn context remains near budget\\n" +
                            "userLength=${prompt.length}\\n" +
                            "submittedPromptLength=${submittedPrompt.length}\\n" +
                            "action=continue with concise response"
                    )
                }
                val imageBytes = imageUri?.let { uri ->
                    openVisionInputStream(uri)?.use { input ->
                        input.readBytes().also { bytes ->
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
                var nativeConversationContainsCurrentTurn = true
                val candidateCall = generated.toolCalls.singleOrNull()
                // Gemma can occasionally emit a tool call copied from the
                // previous turn while answering a normal question. Never let
                // that stale call cause a phone side effect.
                val proposedCall = candidateCall?.takeIf {
                    actionIntentRouter.toolMatchesUserIntent(prompt, history, it)
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
                    resetNativeConversation()
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
                var verifierRequestsLookup = false
                if (isFactualQuestion &&
                    !referenceGrounding.isInsufficientAnswer(localAnswer)
                ) {
                    // This pass is internal and never reaches the user. It
                    // catches confident-looking entity or historical errors
                    // that phrase matching cannot detect.
                    resetNativeConversation()
                    val verdict = engine.generate(
                        prompt = factualityVerifier.buildPrompt(prompt, localAnswer),
                        onToken = {}
                    )
                    verifierRequestsLookup = factualityVerifier.requestsLookup(verdict.text)
                    // The verifier is an isolated internal pass. Do not leave
                    // its prompt in the user conversation.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
                }
                val shouldUseAutomaticFallback =
                    isFactualQuestion &&
                        (referenceGrounding.isInsufficientAnswer(localAnswer) ||
                            verifierRequestsLookup)
                if (shouldUseAutomaticFallback) {
                    val fallbackQuery = turnOrchestrator.automaticFallbackQuery(prompt)
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = promptBuilder.buildGemmaPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
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
                        nativeConversationContainsCurrentTurn = true
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call") ||
                    generated.text.contains("call:MobileActions:")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
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
                nativeConversationHasContext = nativeConversationContainsCurrentTurn
                diagnosticRecorder.record(
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
                nativeConversationHasContext = false
                conversationCharacters = 0
                diagnosticRecorder.record(
                    "Turn failed\n" +
                        "user=${prompt.take(1_000)}\n" +
                        "imageAttached=${imageUri != null}\n" +
                        "error=${error.stackTraceToString().take(4_000)}"
                )
                mainHandler.post { onComplete("I could not load the local model: ${error.message ?: "unknown error"}") }
            }
        }
        conversationJob?.invokeOnCompletion { MainActivity.activeConversationJobs.decrementAndGet() }
    }

private fun MainActivity.openVisionInputStream(uri: Uri): InputStream? {
    return runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}
