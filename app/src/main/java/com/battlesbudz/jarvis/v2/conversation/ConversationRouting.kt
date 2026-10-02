package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.BuildConfig
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ActionTurnRunner
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput

/** Routing inputs are explicit; captions/direct audio never acquire tool or lookup authority. */
internal data class ConversationTurnRequest(
    val prompt: String,
    val history: List<ChatEntry>,
    val conversationId: String,
    val imageAttached: Boolean,
    val audioAttached: Boolean,
    val voiceAttached: Boolean,
    val directVoiceAudio: Boolean,
    val comparisonActive: Boolean,
    val incrementalVoice: IncrementalVoiceInput?,
    val frozenActionPlan: ActionTurnPlan.Ready?,
    val frozenVoiceFinal: Boolean
)

internal data class RoutedConversation(
    val history: List<ChatEntry>,
    val plan: TurnPlan,
    val actionPlan: ActionTurnPlan,
    val textInput: IncrementalVoiceInput?
)

/** Returns null after a safely completed shortcut/rejection; otherwise no inference has run. */
internal suspend fun JarvisRuntime.routeConversation(
    request: ConversationTurnRequest,
    reply: ConversationReply,
    phoneActions: ConversationActions,
    onPhonePlanFinished: (ActionTurnRunner.Outcome) -> Unit
): RoutedConversation? {
    val prompt = request.prompt
    val history = request.history
    val benchmark = reply.benchmark
    val postFinish = reply::postFinish
    // All routing, recall, and summaries must observe the persistent post-memory
    // boundary. Visible transcript stays intact; only prompt context is filtered.
    var safeHistory = conversationHistory.contextAfterMemoryCutoff().filterNot {
        request.directVoiceAudio && com.battlesbudz.jarvis.v2.voice.GemmaAudioInputPolicy.isPendingTranscript(it.role, it.text)
    }
    val turnPlan = if (request.directVoiceAudio || request.comparisonActive || request.imageAttached || request.audioAttached) com.battlesbudz.jarvis.v2.ai.TurnPlan(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT)
        else turnOrchestrator.plan(prompt, safeHistory.map { it.role to it.text })
    if (request.voiceAttached && turnPlan.lookupQuery == null) activeVoiceOutput?.acknowledgeConfirmedTurn()
    // Parse the completed request before any direct shortcut, lookup, or model side effect.
    val requestedActionPlan = request.frozenActionPlan ?: turnPlan.actionPlan
    diagnosticRecorder.record("Action route plan=${requestedActionPlan.javaClass.simpleName} " +
        "steps=${(requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready)?.steps?.map { it.request.name } ?: emptyList<String>()} " +
        "lookup=${turnPlan.lookupQuery != null} " +
        "build=${BuildConfig.VERSION_CODE} user=${prompt.take(1000)} " +
        "condition=${(requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready)?.batteryCondition} " +
        "reason=${(requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Rejected)?.reason}")
    if (requestedActionPlan is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Rejected) {
        reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
        reply.failure = "action_plan_rejected"
        request.incrementalVoice?.close()
        resetNativeConversation()
        val rejection = requestedActionPlan.reason
        turnOrchestrator.recordResponse(prompt, rejection, turnPlan)
        postFinish(rejection)
        return null
    }
    val guardedFrozenVoicePlan = requestedActionPlan as? com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready
    if (request.frozenVoiceFinal && guardedFrozenVoicePlan != null &&
        !guardedFrozenVoicePlan.steps.all { step ->
            com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
                step.sourceClause, step.request.name, step.request.arguments)
        }) {
        reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
        reply.failure = "final_voice_guard_rejected"
        request.incrementalVoice?.close()
        resetNativeConversation()
        val rejection = "I couldn't verify that final spoken phone request. Please say it again."
        diagnosticRecorder.recordImportant("Voice action rejected: final source-clause guard failed")
        postFinish(rejection)
        return null
    }
    var preparedTextInput = request.incrementalVoice?.takeIf { !request.imageAttached && requestedActionPlan !is com.battlesbudz.jarvis.v2.actions.ActionTurnPlan.Ready }
    if (preparedTextInput == null) request.incrementalVoice?.close()
    // The whole literal request is validated before any effect. Text and final voice
    // use the same native path, including models which do not emit function calls.
    if (guardedFrozenVoicePlan != null) {
        val plan = guardedFrozenVoicePlan
        if ((request.voiceAttached || request.frozenVoiceFinal) && !plan.steps.all { step ->
                com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(
                    step.sourceClause, step.request.name, step.request.arguments)
            }) {
            postFinish("I couldn't verify that final spoken phone request. Please say it again.")
            return null
        }
        resetNativeConversation()
        val outcome = phoneActions.runValidated(plan, benchmark)
        if (!outcome.completed) {
            reply.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
            reply.failure = "tool_execution_incomplete"
        }
        lastPhoneActionStatus = com.battlesbudz.jarvis.v2.actions.PhoneActionStatus(
            request.conversationId, prompt, outcome.message)
        onPhonePlanFinished(outcome)
        diagnosticRecorder.recordImportant("Action turn\nbuild=${BuildConfig.VERSION_CODE} user=${prompt.take(1000)}\n" +
            "steps=${plan.steps.map { it.request }} condition=${plan.batteryCondition} matched=${outcome.conditionMatched}\n" +
            "conditionResult=${outcome.conditionResult?.message}\n" +
            "receipts=${outcome.receipts.map { it.request to (it.result.outcome to it.result.message) }}\n" +
            "completed=${outcome.completed} result=${outcome.message}")
        turnOrchestrator.recordResponse(prompt, outcome.message, turnPlan)
        postFinish(outcome.message)
        return null
    }
    val phoneStatusReply = if (!request.directVoiceAudio && !request.imageAttached && !request.audioAttached && !request.comparisonActive)
        com.battlesbudz.jarvis.v2.actions.PhoneActionStatusReply.resolve(prompt, request.conversationId,
            history.map { it.role to it.text }, lastPhoneActionStatus) else null
    if (phoneStatusReply != null) {
        request.incrementalVoice?.close()
        resetNativeConversation()
        diagnosticRecorder.recordImportant("Phone status follow-up: source=executor_receipt modelInvoked=false")
        postFinish(phoneStatusReply)
        return null
    }
    if (!modelStore.verifyIntegrity(modelStore.selectedModel())) {
        request.incrementalVoice?.close()
        conversationEngine?.close()
        conversationEngine = null
        error("The selected model file changed or failed integrity verification. Re-import it.")
    }
    return RoutedConversation(safeHistory, turnPlan, requestedActionPlan, preparedTextInput)
}
