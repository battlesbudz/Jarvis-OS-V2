package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.actions.runNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.InputStream

internal fun JarvisRuntime.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        incrementalVoice: com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput? = null,
        voiceAudio: ByteArray? = null,
        voiceAudioIsComplete: Boolean = true,
        comparison: com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Trial? = null,
        onLatency: (com.battlesbudz.jarvis.v2.diagnostics.TurnLatency) -> Unit = {},
        onActionResult: (String, String, Boolean) -> Unit = { _, _, _ -> },
        audioUri: Uri? = null,
        /** A queue admission freezes authorization before it waits for native ownership. */
        frozenActionPlan: com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready? = null,
        /** Frozen final ASR still requires the same source-clause guard as attached voice input. */
        frozenVoiceFinal: Boolean = false
    ): Job? {
        fun buildTurnPrompt(userPrompt: String, actionResultContext: String?,
                            history: List<ChatEntry>, seedContext: Boolean): String =
            promptBuilder.buildGemmaPrompt(userPrompt, actionResultContext, history, seedContext,
                voice = voiceAudio != null, compactInstructions = (modelStore.selectedModel().contextTokens ?: 4096) < 2048)
        // Smaller Qwen exports have a real 2K/4K cache, not the upstream model's advertised context.
        // Character budgeting remains conservative/approximate; native token limits are authoritative.
        val contextLimit = modelStore.selectedModel().contextTokens?.let {
            minOf(ConversationPolicy.CONVERSATION_COMPACTION_LIMIT, it * 3 - if (imageUri != null) 1800 else 0)
        } ?: ConversationPolicy.CONVERSATION_COMPACTION_LIMIT
        val latencyStarted = System.nanoTime()
        val latencyId = java.util.UUID.randomUUID().toString()
        var loadMs = 0L
        var lookupMs = 0L
        val inferencePasses = mutableListOf<com.battlesbudz.jarvis.v2.diagnostics.InferenceTiming>()
        var firstVisibleMs: Long? = null
        fun elapsed() = (System.nanoTime() - latencyStarted) / 1_000_000
        fun deliverToken(text: String) {
            if (firstVisibleMs == null && text.isNotBlank()) firstVisibleMs = elapsed()
            onToken(text)
        }
        fun finish(text: String) {
            if (firstVisibleMs == null && text.isNotBlank()) firstVisibleMs = elapsed()
            onLatency(com.battlesbudz.jarvis.v2.diagnostics.TurnLatency(latencyId, elapsed(),
                firstVisibleMs, loadMs, lookupMs, inferencePasses.toList()))
            comparison?.put("answer", text)
            onComplete(text)
        }
        if (voiceAudio == null && frozenActionPlan == null && modelStore.isModelOperationActive()) {
            finish("A voice or model operation is still active. Please finish it first.")
            return null
        }
        if (!ConversationWork.activeJobs.compareAndSet(0, 1)) {
            finish("The previous response is still finishing. Please try again in a moment.")
            return null
        }
        val invocation = runtimeScope.launch(Dispatchers.Default) {
            try {
                if (!modelStore.verifyIntegrity(modelStore.selectedModel())) {
                    incrementalVoice?.close()
                    conversationEngine?.close()
                    conversationEngine = null
                    error("The selected model file changed or failed integrity verification. Re-import it.")
                }
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > ConversationPolicy.MAX_USER_PROMPT_CHARS) {
                    diagnosticRecorder.record(
                        "Turn rejected before action routing\\n" +
                            "userLength=${prompt.length}\\n" +
                            "reason=single user message exceeds safe mobile budget"
                    )
                    mainHandler.post {
                        finish(
                            "That request is too large for the local model's safe mobile budget. " +
                                "Please send it in smaller parts."
                        )
                    }
                    return@launch
                }

                var actionResultForGemma: String? = null
                var actionResultMessage: String? = null
                var actionName: String? = null
                val turnPlan = if (comparison != null || imageUri != null || audioUri != null) com.battlesbudz.jarvis.v2.ai.TurnPlan(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT)
                    else turnOrchestrator.plan(prompt, history.map { it.role to it.text })
                if (voiceAudio != null && turnPlan.lookupQuery == null) activeVoiceOutput?.acknowledgeConfirmedTurn()
                val repeatReply = if (imageUri == null && audioUri == null && voiceAudio == null) com.battlesbudz.jarvis.v2.ai.LastReplyRecall.resolve(
                    prompt, history.map { it.role to it.text }
                ) else null
                if (repeatReply != null) {
                    // Speculation may have guessed an older reply. The saved visible
                    // answer is authoritative; repeating it never reruns a phone tool.
                    resetNativeConversation()
                    turnOrchestrator.recordResponse(prompt, repeatReply, turnPlan)
                    diagnosticRecorder.record("Dialogue recall: source=latest_visible_reply chars=${repeatReply.length}")
                    mainHandler.post { finish(repeatReply) }
                    return@launch
                }
                // Parse the completed request before any direct shortcut, lookup, or model side effect.
                val requestedActionPlan = frozenActionPlan ?: turnPlan.actionPlan
                diagnosticRecorder.record("Action route plan=${requestedActionPlan.javaClass.simpleName} " +
                    "steps=${(requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready)?.steps?.map { it.request.name } ?: emptyList<String>()} " +
                    "lookup=${turnPlan.lookupQuery != null}")
                if (requestedActionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Rejected) {
                    incrementalVoice?.close()
                    resetNativeConversation()
                    val rejection = requestedActionPlan.reason
                    turnOrchestrator.recordResponse(prompt, rejection, turnPlan)
                    mainHandler.post { finish(rejection) }
                    return@launch
                }
                val guardedFrozenVoicePlan = requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
                if (frozenVoiceFinal && guardedFrozenVoicePlan != null &&
                    !guardedFrozenVoicePlan.steps.all { step ->
                        com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
                            step.sourceClause, step.request.name, step.request.arguments)
                    }) {
                    incrementalVoice?.close()
                    resetNativeConversation()
                    val rejection = "I couldn't verify that final spoken phone request. Please say it again."
                    diagnosticRecorder.recordImportant("Voice action rejected: final source-clause guard failed")
                    mainHandler.post { finish(rejection) }
                    return@launch
                }
                val textInput = incrementalVoice?.takeIf { imageUri == null && requestedActionPlan !is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready }
                if (textInput == null) incrementalVoice?.close()
                val directRequest = if (comparison != null || imageUri != null || audioUri != null) null else
                    com.battlesbudz.jarvis.v2.actions.DirectAppCommand.parse(prompt)?.takeIf { direct ->
                        (requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready)
                            ?.takeIf { it.steps.size == 1 }?.steps?.single()?.request == direct
                    }
                if (directRequest != null) {
                    resetNativeConversation()
                    val result = kotlinx.coroutines.withContext(Dispatchers.Main) {
                        com.battlesbudz.jarvis.v2.actions.MobileActionPipeline(
                            executor = com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor(
                                this@runConversationInternal,
                                canLaunchDirectly = { activityVisible }
                            )
                        ).execute(directRequest).also {
                            // Persist the synchronous side effect before cancellable Main -> caller dispatch.
                            onActionResult(directRequest.name, it.message, it.succeeded)
                        }
                    }
                    diagnosticRecorder.recordImportant("Action\nuser=${prompt.take(500)}\nrequest=$directRequest\nsucceeded=${result.succeeded}\nresult=${result.message}")
                    turnOrchestrator.recordResponse(prompt, result.message, turnPlan)
                    mainHandler.post { finish(result.message) }
                    return@launch
                }
                val lookupStarted = System.nanoTime()
                val referenceContext = turnPlan.lookupQuery?.let {
                    if (voiceAudio != null) activeVoiceOutput?.acknowledgeConfirmedTurn()
                    referenceGrounding.fetchIfRequested(it)?.context
                }
                if (turnPlan.lookupQuery != null) diagnosticRecorder.recordSummary(
                    "Voice lookup durationMs=${(System.nanoTime() - lookupStarted) / 1_000_000} success=${!referenceContext.isNullOrBlank()}")

                lookupMs += if (turnPlan.lookupQuery != null) (System.nanoTime() - lookupStarted) / 1_000_000 else 0L

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
                        finish("I tried to verify that with Wikipedia, but it was unavailable right now.")
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
                        finish(
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
                val existingPromptSize = buildTurnPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = false
                ).length
                val freshPromptSize = buildTurnPrompt(
                    prompt,
                    actionResultForGemma,
                    history,
                    seedContext = true
                ).length
                val pendingRequestSize = maxOf(existingPromptSize, freshPromptSize) + referenceSize
                var promptHistory = history
                if (conversationCharacters + pendingRequestSize + ConversationPolicy.GENERATION_HEADROOM >
                    contextLimit
                ) {
                    val compactedText = shortTermContext.compactSnapshot(
                        history.map { it.role to it.text }
                    )
                    if (compactedText.isNotBlank()) {
                        shortTermContext.updateSummary(compactedText)
                        sessionPreferences.edit()
                            .putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                            .apply()
                    }
                    // The compacted summary already contains the newest turns.
                    // Do not seed them a second time from the visible transcript.
                    promptHistory = emptyList()
                    resetNativeConversation()
                }

                // LiteRT-LM can retain a text-only native conversation, but
                // Gemma vision is reliable only when the image starts a fresh
                // native conversation. Keep the app transcript/history intact
                // and reseed that history into the fresh conversation below.
                if (imageUri != null) {
                    check(modelStore.selectedModel().supportsVision) {
                        "${modelStore.selectedModel().id} is text-only. Select a model with image input."
                    }
                    if (conversationEngine?.visionEnabled != true) {
                        conversationEngine?.close()
                        conversationEngine = null
                        nativeConversationHasContext = false
                        conversationCharacters = 0
                    } else resetNativeConversation()
                }

                if (audioUri != null) {
                    check(modelStore.selectedModel().supportsAudio) { "This download does not support audio input." }
                    if (conversationEngine?.audioEnabled != true) {
                        conversationEngine?.close()
                        conversationEngine = null
                        nativeConversationHasContext = false
                        conversationCharacters = 0
                    } else resetNativeConversation()
                }

                // Keep the expensive model/GPU engine alive. The replaceable
                // Conversation is reset only when the bounded context needs
                // to be compacted or an isolated retry is required.
                val loadingStarted = System.nanoTime()
                val engineWasLoaded = conversationEngine != null
                val engine = conversationEngine ?: LiteRtLmEngine(
                    modelStore.selectedModel().id,
                    modelStore.fileFor(modelStore.selectedModel()).path,
                    cacheDir.path,
                    useGpu = modelStore.selectedModel().recommendedGpu,
                    tools = if (modelStore.selectedModel().supportsTools)
                        com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all() else emptyList(),
                    visionEnabled = imageUri != null && modelStore.selectedModel().supportsVision,
                    audioEnabled = (voiceAudio != null || audioUri != null) && modelStore.selectedModel().supportsAudio
                ).also {
                    it.initialize()
                    conversationEngine = it
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                if (!engineWasLoaded) loadMs += (System.nanoTime() - loadingStarted) / 1_000_000
                val allowTools = comparison == null && modelStore.selectedModel().supportsTools &&
                    requestedActionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
                if (engine.setToolsEnabled(allowTools)) {
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                diagnosticRecorder.record("Generation tool policy allowed=$allowTools source=current_action_intent")
                val streamGroundedVoice = voiceAudio != null && !referenceContext.isNullOrBlank()
                val voiceRepetitionGuard = if (voiceAudio != null &&
                    actionIntentRouter.classifyActionIntent(prompt, history) == null) {
                    com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard(
                        prompt, history.lastOrNull { it.role == "Jarvis" }?.text,
                        emit = { safe -> mainHandler.post { deliverToken(com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard.speechReady(safe)) } })
                } else null
                if (voiceAudio != null) diagnosticRecorder.record("Voice sentence streaming: " +
                    "suppliedReference=$streamGroundedVoice actionGuard=$allowTools")
                if (streamGroundedVoice) voiceRepetitionGuard?.isPublishable = {
                    !referenceGrounding.isInsufficientAnswer(it)
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    // With supplied evidence, release checked voice sentences as they arrive.
                    // Unverified local-factual drafts and action results retain their final gates.
                    if ((turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT || streamGroundedVoice) &&
                        requestedActionPlan !is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
                    ) {
                        if (voiceRepetitionGuard != null) voiceRepetitionGuard.accept(safeText)
                        else mainHandler.post { deliverToken(safeText) }
                    }
                }
                var seedContext = !nativeConversationHasContext
                var submittedPrompt = buildTurnPrompt(
                    prompt,
                    actionResultForGemma,
                    if (seedContext) promptHistory else emptyList(),
                    seedContext
                )
                turnPlan.activeSubject?.let {
                    submittedPrompt += "\n\nResolved subject for this turn: " + it
                }
                submittedPrompt += referenceContext?.let { "\n\n$it" }.orEmpty()
                if (submittedPrompt.length + ConversationPolicy.GENERATION_HEADROOM >
                    contextLimit
                ) {
                    // A compacted summary is useful background, but it must
                    // never crowd out the current request or retrieved image /
                    // Wikipedia evidence. Retry the fresh session without
                    // seeded history before rejecting the user turn.
                    seedContext = false
                    submittedPrompt = buildTurnPrompt(
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
                if (submittedPrompt.length + ConversationPolicy.GENERATION_HEADROOM >
                    contextLimit
                ) {
                    check(modelStore.selectedModel().contextTokens == null) {
                        "This request exceeds ${modelStore.selectedModel().id}'s small context budget. Please send a shorter request."
                    }
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
                if (voiceAudio != null) {
                    val emptyContextPrompt = buildTurnPrompt(prompt, actionResultForGemma, emptyList(), false)
                    val subjectChars = turnPlan.activeSubject?.let { ("\n\nResolved subject for this turn: " + it).length } ?: 0
                    val referenceChars = referenceContext?.let { it.length + 2 } ?: 0
                    val contextChars = (submittedPrompt.length - emptyContextPrompt.length - subjectChars - referenceChars).coerceAtLeast(0)
                    diagnosticRecorder.recordSummary("Voice prompt parts totalChars=${submittedPrompt.length} " +
                        "baseAndRequestChars=${emptyContextPrompt.length} contextAndDialogueChars=$contextChars " +
                        "subjectChars=$subjectChars referenceChars=$referenceChars audioComplete=$voiceAudioIsComplete " +
                        "scope=assembled_answer_prompt prepared=false")
                    val latestReply = promptHistory.lastOrNull { it.role == "Jarvis" }?.text?.trim()?.take(450)
                    val latestUser = promptHistory.lastOrNull { it.role == "You" }?.text?.trim()?.take(300)
                    diagnosticRecorder.recordSummary("Voice context evidence policy=newest_first_v1 seeded=$seedContext " +
                        "historyEntries=${promptHistory.size} " +
                        "latestUserIncluded=${latestUser?.takeIf { it.isNotBlank() }?.let(submittedPrompt::contains)} " +
                        "latestReplyIncluded=${latestReply?.takeIf { it.isNotBlank() }?.let(submittedPrompt::contains)} " +
                        "latestReplyChars=${latestReply?.length ?: 0} scope=assembled_prompt nativeContext=$nativeConversationHasContext")
                }
                val imageBytes = imageUri?.let { uri ->
                    openVisionInputStream(uri)?.use { input ->
                        com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.readBounded(input)
                    } ?: error("The selected image could not be read.")
                }
                val attachedAudio = audioUri?.let { uri ->
                    openVisionInputStream(uri)?.use { com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.readBounded(it) }
                        ?.also { com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.validateAudio(it) }
                        ?: error("The selected audio could not be read.")
                }
                fun recordInference(label: String, result: com.battlesbudz.jarvis.v2.ai.GenerationResult) {
                    comparison?.put("inference_" + label, "nativeTTFTMs=${result.timeToFirstTokenMs} totalMs=${result.totalGenerationTimeMs} nativeSubmitMs=${result.nativeSubmitMs} firstCallbackMs=${result.firstCallbackMs}")
                    inferencePasses += com.battlesbudz.jarvis.v2.diagnostics.InferenceTiming.from(
                        label, result, prepared = false)
                    diagnosticRecorder.recordSummary(
                        "Inference\n" +
                            "stage=$label\n" +
                            "promptChars=${submittedPrompt.length}\n" +
                            "timeToFirstTokenMs=${result.timeToFirstTokenMs}\n" +
                            "nativeSubmitMs=${result.nativeSubmitMs} firstCallbackMs=${result.firstCallbackMs}\n" +
                            "totalGenerationTimeMs=${result.totalGenerationTimeMs}\n" +
                            "outputTokensEstimated=${result.outputTokens ?: -1}\n" +
                            "streamEvents=${result.streamEvents}\n" +
                            "decodeTokensPerSecondEstimated=${result.decodeTokensPerSecond ?: -1.0}"
                    )
                }
                val directAudioComparison = comparison?.request?.path == com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT
                comparison?.mark("answer_submit")
                val voiceGenerationStarted = System.nanoTime()
                var rawVoiceTokenSeen = false
                val acceptVoiceToken: (String) -> Unit = { token ->
                    if (!rawVoiceTokenSeen && token.isNotBlank()) {
                        comparison?.mark("answer_first_token")
                        rawVoiceTokenSeen = true
                        diagnosticRecorder.recordSummary("Voice generation: first_raw_token_ms=" +
                            ((System.nanoTime() - voiceGenerationStarted) / 1_000_000) +
                            " source=" + if (textInput != null) "incremental_text" else "live_text")
                    }
                    streamFilter.accept(token)
                }
                diagnosticRecorder.recordSummary("Inference input: mode=" +
                    (if (directAudioComparison) "diagnostic_direct_audio" else if (textInput != null) "incremental_text"
                        else if (voiceAudio != null) "voice_text"
                        else if (imageBytes != null) "image_text" else if (attachedAudio != null) "audio_file_text" else "text") +
                    " audioBytes=${if (directAudioComparison) voiceAudio?.size ?: 0 else 0} retainedAudioBytes=${voiceAudio?.size ?: 0} promptChars=${submittedPrompt.length}" +
                    " nativeAudioEncodeMs=${if (directAudioComparison) "unavailable" else "not_used"} queueMs=unavailable")
                var incrementalFallbackUsed = false
                var generated = if (directAudioComparison) {
                    engine.generateAudio(submittedPrompt, requireNotNull(voiceAudio), acceptVoiceToken)
                } else if (textInput != null) {
                    engine.onPromptSubmitted(submittedPrompt, 0)
                    textInput.answerWithTextFallback(submittedPrompt, acceptVoiceToken) { error ->
                        diagnosticRecorder.recordSummary("Voice incremental fallback: reason=${error.javaClass.simpleName} " +
                            "message=${error.message?.take(300)} policy=final_text_once audioBytes=0")
                        incrementalFallbackUsed = true
                        engine.generate(submittedPrompt, acceptVoiceToken)
                    }
                } else if (voiceAudio != null) {
                    engine.generate(prompt = submittedPrompt, onToken = acceptVoiceToken)
                } else if (attachedAudio != null) {
                    engine.generateAudio(submittedPrompt, attachedAudio, streamFilter::accept)
                } else if (imageBytes != null) {
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
                recordInference("answer", generated)
                var nativeConversationContainsCurrentTurn = textInput == null || incrementalFallbackUsed
                val actionPlan = requestedActionPlan
                if (actionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready && generated.toolCalls.isNotEmpty()) {
                    // Final transcript only: every source clause is independently guarded before dispatch.
                    val voiceAllowed = (voiceAudio == null && !frozenVoiceFinal) || actionPlan.steps.all { step ->
                        com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
                            step.sourceClause, step.request.name, step.request.arguments)
                    }
                    if (voiceAllowed) {
                        val coordinator = com.battlesbudz.jarvis.v2.actions.ActionTurnRunner(
                            executor = com.battlesbudz.jarvis.v2.actions.MobileActionExecutor {
                                error("ActionTurnRunner dispatch is supplied by the conversation runtime")
                            }
                        )
                        val outcome = coordinator.runNative(
                            plan = actionPlan,
                            initialCalls = generated.toolCalls,
                            dispatch = { request ->
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    // Receipt and voice/text persistence occur in the same synchronous Main block.
                                    com.battlesbudz.jarvis.v2.actions.MobileActionPipeline(
                                        executor = com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor(
                                            this@runConversationInternal, canLaunchDirectly = { activityVisible }
                                        )
                                    ).execute(request).also { onActionResult(request.name, it.message, it.succeeded) }
                                }
                            },
                            nextCalls = { batch ->
                                val nativeResults = batch.map { receipt ->
                                    val call = com.battlesbudz.jarvis.v2.ai.ToolCall(
                                        receipt.request.name, org.json.JSONObject(receipt.request.arguments).toString())
                                    val context = promptBuilder.buildToolResultContext(prompt, receipt.request.name,
                                        receipt.result.message, receipt.result.succeeded)
                                    call to context
                                }
                                generated = engine.sendToolResults(nativeResults, streamFilter::accept)
                                recordInference("tool response", generated)
                                generated.toolCalls
                            }
                        )
                        actionName = actionPlan.steps.joinToString(",") { it.request.name }
                        actionResultMessage = outcome.message
                        actionResultForGemma = outcome.message
                        diagnosticRecorder.recordImportant("Action turn\nuser=${prompt.take(500)}\n" +
                            "steps=${actionPlan.steps.map { it.request }}\nreceipts=${outcome.receipts}\n" +
                            "completed=${outcome.completed} result=${outcome.message}")
                    } else {
                        actionResultMessage = "I couldn't verify the requested phone actions."
                    }
                } else if (actionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready) {
                    // An action turn never falls back to model prose or claims without a verified receipt.
                    actionResultMessage = "I couldn't verify the requested phone actions."
                }
                // A rejected tool call can sometimes contain no answer text at all.
                // Retry that turn as ordinary conversation so a normal question
                // never falls through to a phone-action error message.
                if (generated.toolCalls.isNotEmpty() && actionResultMessage == null &&
                    cleanAssistantText(generated.text).isBlank()
                ) {
                    resetNativeConversation()
                    engine.setToolsEnabled(false)
                    diagnosticRecorder.recordSummary("Rejected tool response reason=does_not_match_current_intent retryToolsEnabled=false")
                    val retryPrompt = submittedPrompt + """
                        
                        The previous output contained an invalid tool call. Answer the user's current message directly as normal text. Do not call a tool.
                    """.trimIndent()
                    generated = if (attachedAudio != null) {
                        engine.generateAudio(retryPrompt, attachedAudio, streamFilter::accept)
                    } else if (imageBytes != null) {
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
                    recordInference("invalid tool retry", generated)
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
                    recordInference("factuality check", verdict)
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
                    val fallbackStarted = System.nanoTime()
                    val fallbackContext = referenceGrounding.fetchIfRequested(fallbackQuery)?.context
                    lookupMs += (System.nanoTime() - fallbackStarted) / 1_000_000
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = buildTurnPrompt(
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
                        recordInference("reference fallback", generated)
                        nativeConversationContainsCurrentTurn = true
                    }
                }
                val rawControlOutput = generated.toolCalls.isNotEmpty() || generated.text.contains("tool_call>") ||
                    generated.text.contains("start_function_call")
                if (rawControlOutput) {
                    // Do not carry protocol text into the next turn.
                    resetNativeConversation()
                    nativeConversationContainsCurrentTurn = false
                }
                var cleanedResponse = cleanAssistantText(generated.text)
                val requiresReference = turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST ||
                    turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.EXPLICIT_LOOKUP ||
                    turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.LOOKUP_CONFIRMATION
                if (requiresReference && referenceGrounding.isInsufficientAnswer(cleanedResponse) &&
                    (voiceRepetitionGuard?.acceptedSentences ?: 0) == 0) {
                    // Never expose a local knowledge-base disclaimer for a
                    // person/entity question. Re-query the reference APIs once
                    // and regenerate from the fresh evidence before replying.
                    val retryQuery = turnPlan.lookupQuery ?: prompt
                    val retryLookupStarted = System.nanoTime()
                    val retryContext = referenceGrounding.fetchIfRequested(retryQuery)?.context
                    lookupMs += (System.nanoTime() - retryLookupStarted) / 1_000_000
                    if (!retryContext.isNullOrBlank()) {
                        diagnosticRecorder.record(
                            "Reference retry after knowledge-gap draft\n" +
                                "user=${prompt.take(1_000)}\n" +
                                "lookupQuery=${retryQuery.take(1_000)}"
                        )
                        resetNativeConversation()
                        voiceRepetitionGuard?.discardPending()
                        val retryPrompt = buildTurnPrompt(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
                        ) + "\n\n" + retryContext + "\n\n" +
                            "The previous draft was not acceptable. Answer from the reference evidence above. Do not mention your knowledge base or ask whether to search."
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
                        nativeConversationContainsCurrentTurn = true
                        recordInference("reference retry", generated)
                        cleanedResponse = cleanAssistantText(generated.text)
                    }
                }
                if (requiresReference && referenceGrounding.isInsufficientAnswer(cleanedResponse) &&
                    (voiceRepetitionGuard?.acceptedSentences ?: 0) == 0) {
                    cleanedResponse = "I couldn't produce a verified answer from Wikipedia right now. Please try again."
                }
                if (voiceRepetitionGuard != null && actionResultMessage == null) {
                    cleanedResponse = voiceRepetitionGuard.finish(cleanedResponse)
                    if (voiceRepetitionGuard.needsRepair) {
                        diagnosticRecorder.recordImportant("Voice repetition blocked: sentences=${voiceRepetitionGuard.suppressedSentences}; streaming one bounded read-only repair.")
                        val repairStarted = System.nanoTime()
                        val beforeRepair = voiceRepetitionGuard.text.length
                        resetNativeConversation()
                        nativeConversationContainsCurrentTurn = false
                        try {
                            val repairPrompt = buildTurnPrompt(prompt, null, history, seedContext = true) +
                                "\n" + referenceContext.orEmpty() + "\n" +
                                "\nYour previous draft repeated the user or an earlier reply and was suppressed. " +
                                "Give a NEW direct answer to the CURRENT question in one or two sentences. " +
                                "Do not recap, apologize, quote earlier sentences, or call tools. " +
                                "Resolve follow-ups using the dialogue above."
                            // Disable native tool production as well as keeping repair outside dispatch.
                            engine.setToolsEnabled(false)
                            val repair = com.battlesbudz.jarvis.v2.voice.VoiceRepetitionRepair.run(voiceRepetitionGuard) { emit ->
                                engine.generate(prompt = repairPrompt, onToken = emit)
                            }
                            repair.generation?.let { recordInference("repetition repair", it) }
                            diagnosticRecorder.recordImportant("Voice repetition repair ended reason=${repair.reason} " +
                                "budgetMs=10000 nativeCancellationWaitSeparate=true")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            diagnosticRecorder.record("Voice repetition repair failed: ${error.message}")
                        } finally {
                            resetNativeConversation()
                            nativeConversationContainsCurrentTurn = false
                        }
                        if (voiceRepetitionGuard.text.length == beforeRepair) {
                            for (fallback in listOf("I couldn't produce a fresh answer to that.",
                                "I'm stuck on that question at the moment.", "I don't have a useful new answer yet.")) {
                                voiceRepetitionGuard.accept(fallback)
                                voiceRepetitionGuard.finish()
                                if (voiceRepetitionGuard.text.length > beforeRepair) break
                            }
                        }
                        cleanedResponse = voiceRepetitionGuard.text
                        diagnosticRecorder.recordImportant("Voice repetition guard: suppressed=${voiceRepetitionGuard.suppressedSentences} " +
                            "acceptedChars=${cleanedResponse.length} repairMs=${(System.nanoTime() - repairStarted) / 1_000_000}")
                    }
                }
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
                val finalResponse = actionResultMessage ?: if (actionIntentRouter.classifyActionIntent(prompt, history) != null) {
                    "I couldn't execute that phone action. Please ask again with the app name or exact setting."
                } else if (repeatedFragment) {
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
                diagnosticRecorder.recordImportant(
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
                mainHandler.post { finish(finalResponse) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                comparison?.put("generation_error", error.message ?: error.javaClass.simpleName)
                incrementalVoice?.close()
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
                mainHandler.post { finish("I could not load the local model: ${error.message ?: "unknown error"}") }
            } finally {
                incrementalVoice?.close()
            }
        }
        conversationJob = invocation
        invocation.invokeOnCompletion { ConversationWork.activeJobs.decrementAndGet() }
        return invocation
    }

private fun JarvisRuntime.openVisionInputStream(uri: Uri): InputStream? {
    return runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}
