package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.diagnostics.newPipelineBenchmark
import com.battlesbudz.jarvis.v2.diagnostics.finishPipelineResources
import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

internal fun JarvisRuntime.runConversationInternal(
        prompt: String,
        history: List<ChatEntry>,
        imageUri: Uri?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        incrementalVoice: com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput? = null,
        voiceAudio: ByteArray? = null,
        voiceAudioIsComplete: Boolean = true,
        /** Original audio is authoritative; caption text must never route tools or lookup. */
        directVoiceAudio: Boolean = false,
        replyIdentity: String? = null,
        conversationIdentity: String? = null,
        callIdentity: String? = null,
        comparison: com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Trial? = null,
        onLatency: (com.battlesbudz.jarvis.v2.diagnostics.TurnLatency) -> Unit = {},
        onLiveInference: (submittedAtMs: Long?, firstTokenAtMs: Long?, estimatedTokensPerSecond: Double?, durable: Boolean) -> Unit = { _, _, _, _ -> },
        onActionResult: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onPhonePlanFinished: (com.battlesbudz.jarvis.v2.actions.ActionTurnRunner.Outcome) -> Unit = {},
        audioUri: Uri? = null,
        /** A queue admission freezes authorization before it waits for native ownership. */
        frozenActionPlan: com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready? = null,
        /** Frozen final ASR still requires the same source-clause guard as attached voice input. */
        frozenVoiceFinal: Boolean = false,
        /** The call owner holds the one model lease and joins this invocation before restart. */
        callOwned: Boolean = false,
        /** Binds an ordinary answer's exact voice output to its mutation/expiry delivery ticket. */
        onMemoryBound: (com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence.Ticket, MemoryTurnContext) -> Unit = { _, _ -> },
        benchmarkCapture: com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture? = null
    ): Job? {
        val phoneTaskConversationId = conversationIdentity ?: conversationHistory.current.value.id
        val phoneActions = ConversationActions(
            executor = { com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor(
                this, canLaunchDirectly = { activityVisible }, onDiagnostic = diagnosticRecorder::recordImportant) },
            admit = ::admitPhoneTask, execute = ::executePhoneAction, cancelUnfinished = ::cancelPhoneTask,
            conversationId = phoneTaskConversationId, onActionResult = onActionResult)
        val contextLimit = ConversationPrompt.contextLimit(modelStore.selectedModel().contextTokens, imageUri != null)
        val latencyStarted = System.nanoTime()
        val latencyId = replyIdentity ?: java.util.UUID.randomUUID().toString()
        val benchmark = benchmarkCapture ?: newPipelineBenchmark(latencyId,
            if (voiceAudio != null) "voice_reply" else if (callOwned) "typed_in_call" else "text")
        if (benchmarkCapture == null) {
            benchmark.configuration("reply_id", latencyId)
            benchmark.configuration("conversation_id", phoneTaskConversationId)
        }
        val ownsBenchmark = benchmarkCapture == null
        val reply = ConversationReply(latencyId, benchmark, ownsBenchmark,
            memoryDeliveryFence, post = { mainHandler.post(it) }, onToken, onComplete, onLatency,
            onAnswer = { comparison?.put("answer", it) },
            finishOwnedBenchmark = { capture, outcome, failure ->
                finishPipelineResources(capture)
                capture.finish(outcome, callId = callIdentity, failureCode = failure)
                    ?.let { pipelineBenchmarkStore.append(it) }
            }, startedNanos = latencyStarted)
        val turnPrompt = ConversationPrompt(promptBuilder, voiceAudio != null,
            contextTokens = { modelStore.selectedModel().contextTokens }, memoryContext = { reply.memoryContext })
        var benchmarkEngine: LiteRtLmEngine? = null
        val inferencePasses = reply.inferencePasses
        val postFinish = reply::postFinish
        val postToken = reply::postToken
        if (voiceAudio == null && frozenActionPlan == null && !callOwned && modelStore.isModelOperationActive()) {
            reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
            reply.failure = "model_operation_busy"
            reply.finish("A voice or model operation is still active. Please finish it first.")
            reply.finishBenchmark()
            return null
        }
        if (!ConversationWork.activeJobs.compareAndSet(0, 1)) {
            reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
            reply.failure = "conversation_busy"
            reply.finish("The previous response is still finishing. Please try again in a moment.")
            reply.finishBenchmark()
            return null
        }
        val invocation = runtimeScope.launch(Dispatchers.Default) {
            var lastLiveRate: Double? = null
            benchmark.mark("request_processing_started")
            benchmark.metric("request_queue_ms", reply.elapsed())
            try {
                // Reject only an exceptionally large single message before
                // routing or executing a phone side effect. Retained history is
                // handled by compaction below and must not reject a short follow-up.
                if (prompt.length > ConversationPolicy.MAX_USER_PROMPT_CHARS) {
                    reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
                    reply.failure = "request_too_large"
                    diagnosticRecorder.record(
                        "Turn rejected before action routing\\n" +
                            "userLength=${prompt.length}\\n" +
                            "reason=single user message exceeds safe mobile budget"
                    )
                    postFinish(
                            "That request is too large for the local model's safe mobile budget. " +
                                "Please send it in smaller parts."
                    )
                    return@launch
                }

                var actionResultForGemma: String? = null
                var actionResultMessage: String? = null
                var actionName: String? = null
                val request = ConversationTurnRequest(prompt, history, phoneTaskConversationId,
                    imageUri != null, audioUri != null, voiceAudio != null, directVoiceAudio,
                    comparison != null, incrementalVoice, frozenActionPlan, frozenVoiceFinal)
                val routed = routeConversation(request, reply, phoneActions, onPhonePlanFinished) ?: return@launch
                val prepared = prepareConversationContext(request, routed, reply, turnPrompt, contextLimit,
                    onMemoryBound) ?: return@launch
                val safeHistory = prepared.history
                val effectiveHistory = prepared.history
                val turnPlan = routed.plan
                val requestedActionPlan = routed.actionPlan
                val memoryTurnContext = prepared.memoryContext
                val memoryHistoryInvalidated = prepared.memoryHistoryInvalidated
                val captureReceipt = prepared.captureReceipt
                val referenceContext = prepared.referenceContext
                val personalMemoryRecall = prepared.personalMemoryRecall
                val textInput = prepared.textInput
                val promptHistory = prepareConversationHistory(effectiveHistory, memoryHistoryInvalidated,
                    turnPrompt.pendingSize(prompt, actionResultForGemma, effectiveHistory, referenceContext), contextLimit)

                val engine = loadConversationEngine(imageUri != null, audioUri != null, voiceAudio != null, reply)
                benchmarkEngine = engine
                engine.onBenchmarkSubmission = benchmark::submission
                engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.ANSWER
                if (voiceAudio == null && !callOwned) {
                    var textSubmission = 0
                    engine.onPromptSubmitted = { exact, audioBytes ->
                        diagnosticRecorder.recordInferencePrompt("turn=$latencyId submission=${++textSubmission} mode=text " +
                            "recordedByBuild=${BuildConfig.VERSION_NAME} model=${engine.modelId} " +
                            "audioBytes=$audioBytes promptChars=${exact.length} historyEntries=${safeHistory.size} " +
                            "capture=${captureReceipt?.outcome ?: "unavailable"}\n${engine.inputContextDescription()}\n" +
                            "--- Exact submitted text begins ---\n$exact\n--- Exact submitted text ends ---")
                    }
                }

                val allowTools = comparison == null && modelStore.selectedModel().supportsTools &&
                    requestedActionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
                if (engine.setToolsEnabled(allowTools)) {
                    nativeConversationHasContext = false
                    conversationCharacters = 0
                }
                diagnosticRecorder.record("Generation tool policy allowed=$allowTools source=current_action_intent")
                val streamGroundedVoice = voiceAudio != null && !referenceContext.isNullOrBlank()
                val voiceRepetitionGuard = if (requestedActionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.NotAction && imageUri == null && audioUri == null) {
                    com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard(
                        prompt, safeHistory.lastOrNull { it.role == "Jarvis" }?.text,
                        emit = { safe -> postToken(if (voiceAudio != null) com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard.speechReady(safe) else safe) }).also { it.preserveFormatting = voiceAudio == null }
                } else null
                if (voiceAudio != null) diagnosticRecorder.record("Voice sentence streaming: " +
                    "suppliedReference=$streamGroundedVoice actionGuard=$allowTools")
                voiceRepetitionGuard?.isPublishable = { candidate ->
                    val reason = com.battlesbudz.jarvis.v2.chat.AnswerQualityPolicy.rejection(prompt, candidate, safeHistory.lastOrNull { it.role == "Jarvis" }?.text)
                    if (reason != null) diagnosticRecorder.recordSummary("Answer draft suppressed reason=$reason turn=$latencyId")
                    reason == null && (!streamGroundedVoice || !referenceGrounding.isInsufficientAnswer(candidate))
                }
                val streamFilter = AssistantStreamFilter { safeText ->
                    // With supplied evidence, release checked voice sentences as they arrive.
                    // Unverified local-factual drafts and action results retain their final gates.
                    if ((turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT || streamGroundedVoice) &&
                        requestedActionPlan !is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
                    ) {
                        if (voiceRepetitionGuard != null) voiceRepetitionGuard.accept(safeText)
                        else postToken(safeText)
                    }
                }
                val submission = turnPrompt.assemble(prompt, actionResultForGemma, promptHistory,
                    seedContext = !nativeConversationHasContext, activeSubject = turnPlan.activeSubject,
                    resolvedQuestion = turnPlan.resolvedQuestion, reference = referenceContext, limit = contextLimit)
                val seedContext = submission.seeded
                val submittedPrompt = submission.text
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
                    val emptyContextPrompt = turnPrompt.build(prompt, actionResultForGemma, emptyList(), false)
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
                val attachments = readConversationAttachments(imageUri, audioUri, benchmark, ::openConversationInputStream)
                val imageBytes = attachments.image
                val attachedAudio = attachments.audio
                fun recordInference(label: String, result: com.battlesbudz.jarvis.v2.ai.GenerationResult) {
                    benchmark.metric("inference_passes_completed", inferencePasses.size + 1)
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
                val directAudioComparison = directVoiceAudio || comparison?.request?.path == com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison.Path.GEMMA_DIRECT
                comparison?.mark("answer_submit")
                // Native edges are durable; interim decode rate is throttled progress only.
                val liveRate = com.battlesbudz.jarvis.v2.ai.LiveTokenRateEstimator {
                    System.nanoTime() / 1_000_000
                }
                engine.onInferenceProgress = { progress ->
                    progress.submittedAtMs?.let { onLiveInference(it, null, null, true) }
                    progress.firstRawTokenAtMs?.let {
                        liveRate.rawToken(it)
                        comparison?.mark("answer_first_token")
                        onLiveInference(null, it, null, true)
                    }
                }
                val acceptVoiceToken: (String) -> Unit = { token ->
                    liveRate.addRawChunk(token)?.let {
                        lastLiveRate = it
                        onLiveInference(null, null, it, false)
                    }
                    streamFilter.accept(token)
                }
                diagnosticRecorder.recordSummary("Inference input: mode=" +
                    (if (directAudioComparison) if (directVoiceAudio) "gemma_direct_audio" else "diagnostic_direct_audio" else if (textInput != null) "incremental_text"
                        else if (voiceAudio != null) "voice_text"
                        else if (imageBytes != null) "image_text" else if (attachedAudio != null) "audio_file_text" else "text") +
                    " audioBytes=${if (directAudioComparison) voiceAudio?.size ?: 0 else attachedAudio?.size ?: 0} retainedAudioBytes=${voiceAudio?.size ?: 0} promptChars=${submittedPrompt.length}" +
                    " nativeAudioEncodeMs=${if (directAudioComparison || attachedAudio != null) "unavailable" else "not_used"} queueMs=unavailable")
                val inferenceInput = ConversationInput(voiceAudio, directAudioComparison, textInput, imageBytes, attachedAudio)
                var runawayLoopStopped = false
                var generated = try {
                    inferenceInput.generate(engine, submittedPrompt, acceptVoiceToken) { error ->
                        diagnosticRecorder.recordSummary("Voice incremental fallback: reason=${error.javaClass.simpleName} " +
                            "message=${error.message?.take(300)} policy=final_text_once audioBytes=0")
                    }
                } catch (loop: com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard.RunawayLoop) {
                    // The adapter cancels and joins native work before this catch. The guard
                    // never publishes its looping suffix, so the speech channel contains only
                    // accepted text; preserve that opening while restarting a bounded answer.
                    runawayLoopStopped = true
                    benchmark.configuration("generation_recovery_reason", "runaway_repetition")
                    diagnosticRecorder.recordImportant("Runaway repetition cancelled before speech publication; preserving valid opening.")
                    com.battlesbudz.jarvis.v2.ai.GenerationResult(voiceRepetitionGuard?.text.orEmpty(), -1L, null)
                }
                if (!runawayLoopStopped) recordInference("answer", generated)
                onLiveInference(null, null, generated.decodeTokensPerSecond ?: lastLiveRate, true)
                var nativeConversationContainsCurrentTurn = inferenceInput.nativeConversationContainsTurn
                val actionPlan = requestedActionPlan
                if (actionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready && generated.toolCalls.isNotEmpty()) {
                    // Final transcript only: every source clause is independently guarded before dispatch.
                    val voiceAllowed = (voiceAudio == null && !frozenVoiceFinal) || actionPlan.steps.all { step ->
                        com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
                            step.sourceClause, step.request.name, step.request.arguments)
                    }
                    if (voiceAllowed) {
                        val outcome = phoneActions.runNative(
                            plan = actionPlan,
                            initialCalls = generated.toolCalls,
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
                    engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.RETRY
                    generated = inferenceInput.retry(engine, retryPrompt, streamFilter::accept)
                    recordInference("invalid tool retry", generated)
                }
                val localAnswer = cleanAssistantText(generated.text)
                val isFactualQuestion =
                    referenceContext == null &&
                        actionName == null &&
                        turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST && !personalMemoryRecall
                var verifierRequestsLookup = false
                if (isFactualQuestion &&
                    !referenceGrounding.isInsufficientAnswer(localAnswer)
                ) {
                    // This pass is internal and never reaches the user. It
                    // catches confident-looking entity or historical errors
                    // that phrase matching cannot detect.
                    resetNativeConversation()
                    engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.DRAFT
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
                    reply.lookupMs += (System.nanoTime() - fallbackStarted) / 1_000_000
                    if (!fallbackContext.isNullOrBlank()) {
                        resetNativeConversation()
                        val fallbackPrompt = turnPrompt.build(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
                        ) + "\n\n" + fallbackContext
                        engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.RETRY
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
                val requiresReference = (!personalMemoryRecall && turnPlan.kind == com.battlesbudz.jarvis.v2.ai.TurnKind.FACTUAL_LOCAL_FIRST) ||
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
                    reply.lookupMs += (System.nanoTime() - retryLookupStarted) / 1_000_000
                    if (!retryContext.isNullOrBlank()) {
                        diagnosticRecorder.record(
                            "Reference retry after knowledge-gap draft\n" +
                                "user=${prompt.take(1_000)}\n" +
                                "lookupQuery=${retryQuery.take(1_000)}"
                        )
                        resetNativeConversation()
                        voiceRepetitionGuard?.discardPending()
                        val retryPrompt = turnPrompt.build(
                            prompt,
                            null,
                            promptHistory,
                            seedContext = true
                        ) + "\n\n" + retryContext + "\n\n" +
                            "The previous draft was not acceptable. Answer from the reference evidence above. Do not mention your knowledge base or ask whether to search."
                        engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.RETRY
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
                    cleanedResponse = "I couldn't produce a verified answer from the available reference evidence. Please try again."
                }
                if (voiceRepetitionGuard != null && actionResultMessage == null) {
                    cleanedResponse = try { voiceRepetitionGuard.finish(cleanedResponse) }
                    catch (loop: com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard.RunawayLoop) {
                        runawayLoopStopped = true
                        benchmark.configuration("generation_recovery_reason", "runaway_repetition")
                        voiceRepetitionGuard.text
                    }
                    if (voiceRepetitionGuard.needsRepair) {
                        diagnosticRecorder.recordImportant("Voice repetition blocked: sentences=${voiceRepetitionGuard.suppressedSentences}; streaming one bounded read-only repair.")
                        val repairStarted = System.nanoTime()
                        val beforeRepair = voiceRepetitionGuard.text.length
                        resetNativeConversation()
                        nativeConversationContainsCurrentTurn = false
                        try {
                            val repairPrompt = turnPrompt.build(prompt, null, promptHistory, seedContext = true) +
                                "\n" + referenceContext.orEmpty() + "\n" +
                                "\nYour previous draft repeated the user or an earlier reply and was suppressed. " +
                                "Give a NEW direct answer to the CURRENT question in one or two sentences. " +
                                "Already delivered opening: ${voiceRepetitionGuard.text.take(600)}. Continue without repeating it. " +
                                "Do not recap, apologize, quote earlier sentences, or call tools. " +
                                "Resolve follow-ups using the dialogue above. " + com.battlesbudz.jarvis.v2.chat.AnswerQualityPolicy.repairInstruction("repetition_or_unhelpful_draft")
                            // Disable native tool production as well as keeping repair outside dispatch.
                            engine.setToolsEnabled(false)
                            engine.benchmarkPurpose = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose.RETRY
                            val repair = com.battlesbudz.jarvis.v2.voice.VoiceRepetitionRepair.run(voiceRepetitionGuard) { emit ->
                                if (directAudioComparison) engine.generateAudio(repairPrompt, requireNotNull(voiceAudio), emit)
                                else engine.generate(prompt = repairPrompt, onToken = emit)
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
                            voiceRepetitionGuard.beginRepair()
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
                if (memoryTurnContext?.let(::isMemoryTurnCurrent) == false) {
                    memoryDeliveryFence.invalidate()
                    // The owner is now at a safe native boundary. Do not preserve or speak an
                    // answer assembled from erased/corrected approved memory.
                    resetNativeConversation()
                    shortTermContext.clear()
                    postFinish("Memory changed while I was responding. Please ask again.")
                    return@launch
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
                postFinish(finalResponse)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
                reply.failure = cancelled.javaClass.simpleName
                phoneActions.cancel()
                lastLiveRate?.let { onLiveInference(null, null, it, true) }
                throw cancelled
            } catch (error: Throwable) {
                reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
                reply.failure = error.javaClass.simpleName
                phoneActions.cancel()
                lastLiveRate?.let { onLiveInference(null, null, it, true) }
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
                postFinish("I could not load the local model: ${error.message ?: "unknown error"}")
            } finally {
                benchmark.mark("request_processing_finished")
                reply.finishBenchmark()
                if (ownsBenchmark) benchmarkEngine?.onBenchmarkSubmission = {}
                conversationEngine?.onInferenceProgress = {}
                if (voiceAudio == null && !callOwned) conversationEngine?.onPromptSubmitted = { _, _ -> }
                incrementalVoice?.close()
            }
        }
        conversationJob = invocation
        invocation.invokeOnCompletion { ConversationWork.activeJobs.decrementAndGet() }
        return invocation
    }
