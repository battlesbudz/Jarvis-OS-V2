package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.ToolTaskAttempt
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskState
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState

/** Presentation only: a pose never starts, approves, retries or completes an operation. */
internal enum class WispActivity {
    READY, LISTENING, THINKING, SPEAKING, CONNECTING, CHECKING, EDITING,
    APPROVAL, SUCCESS, ERROR, PAUSED
}

internal data class WispPresentation(
    val activity: WispActivity,
    val label: String,
    val detail: String? = null,
    val taskKey: String? = null
)

internal data class WispViewport(val widthDp: Int, val heightDp: Int)

internal object WispPresenter {
    /** Call ownership controls space, independently of a temporary pose or microphone pause. */
    fun viewport(armed: Boolean, callState: VoiceSessionState, compact: Boolean): WispViewport {
        val activeCall = armed && when (callState) {
            VoiceSessionState.PASSIVE_LISTENING, VoiceSessionState.ENDED -> false
            VoiceSessionState.ACTIVELY_LISTENING, VoiceSessionState.PROCESSING,
            VoiceSessionState.EXECUTING_ACTION, VoiceSessionState.SPEAKING,
            VoiceSessionState.WAITING_FOR_CONFIRMATION, VoiceSessionState.INTERRUPTED -> true
        }
        return when {
            compact && activeCall -> WispViewport(138, 80)
            compact -> WispViewport(116, 66)
            activeCall -> WispViewport(204, 128)
            else -> WispViewport(168, 104)
        }
    }

    fun present(
        conversationId: String,
        journal: ToolTaskJournal?,
        taskError: String?,
        chatBusy: Boolean,
        armed: Boolean,
        phase: VoicePhase,
        callState: VoiceSessionState,
        microphonePaused: Boolean,
        observedActivity: WispPresentation? = null,
        receipt: WispPresentation? = null
    ): WispPresentation {
        val attempts = relevantAttempts(journal, conversationId)
        // Attention is more important than motion. Unknown outcomes must never look successful.
        val urgent = attempts.lastOrNull { it.state == ToolTaskState.WAITING_APPROVAL }
            ?: attempts.lastOrNull { it.state == ToolTaskState.UNKNOWN_OUTCOME && !it.reconciled }
        if (urgent != null) return task(urgent)
        attempts.lastOrNull { it.state == ToolTaskState.RUNNING }?.let { return task(it) }
        if (taskError != null) return WispPresentation(WispActivity.ERROR, "Task needs attention", taskError)
        val recentError = observedActivity?.takeIf { it.activity == WispActivity.ERROR }
        if (observedActivity != null && recentError == null) return observedActivity
        if (armed && callState == VoiceSessionState.WAITING_FOR_CONFIRMATION)
            return WispPresentation(WispActivity.APPROVAL, "Waiting for you")
        if (armed && phase == VoicePhase.SPEAKING)
            return WispPresentation(WispActivity.SPEAKING, "Speaking")
        attempts.lastOrNull { it.state in pendingStates && !it.reconciled }?.let { return task(it) }
        if (chatBusy) return WispPresentation(WispActivity.THINKING, "Thinking")
        if (receipt != null) return receipt
        if (recentError != null && (!armed || phase == VoicePhase.IDLE)) return recentError
        if (!armed) return WispPresentation(WispActivity.READY, "Ready")
        if (microphonePaused || phase == VoicePhase.PAUSED)
            return WispPresentation(WispActivity.PAUSED, "Microphone paused")
        return when (phase) {
            VoicePhase.LISTENING -> WispPresentation(WispActivity.LISTENING, "Listening")
            VoicePhase.WAKE -> WispPresentation(WispActivity.LISTENING, "Say “Hey Jarvis”")
            VoicePhase.THINKING -> WispPresentation(WispActivity.THINKING, "Thinking")
            VoicePhase.PREPARING, VoicePhase.WAKING -> WispPresentation(WispActivity.THINKING, phase.label)
            else -> when (callState) {
                VoiceSessionState.EXECUTING_ACTION -> WispPresentation(WispActivity.THINKING, "Working")
                VoiceSessionState.INTERRUPTED -> WispPresentation(WispActivity.PAUSED, "Reply interrupted")
                else -> WispPresentation(WispActivity.READY, "Ready")
            }
        }
    }

    fun audioLevel(activity: WispActivity, phase: VoicePhase, armed: Boolean,
        microphonePaused: Boolean, microphone: Float, playback: Float): Float {
        val level = when {
            !armed -> 0f
            activity == WispActivity.SPEAKING && phase == VoicePhase.SPEAKING -> playback
            activity == WispActivity.LISTENING && !microphonePaused &&
                phase in setOf(VoicePhase.LISTENING, VoicePhase.WAKE) -> microphone
            else -> 0f
        }
        return if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    }

    internal fun relevantAttempts(journal: ToolTaskJournal?, conversationId: String): List<ToolTaskAttempt> {
        val groups = journal?.groups.orEmpty().filter { it.conversationId == conversationId }.mapTo(hashSetOf()) { it.id }
        return journal?.attempts.orEmpty().filter { it.groupId in groups || it.groupId == null && !it.reconciled }
    }

    internal fun task(attempt: ToolTaskAttempt): WispPresentation {
        val key = "${attempt.id}:${attempt.generation}:${attempt.state}"
        val description = when (attempt.request.name) {
            "read_battery" -> "Checking battery"
            "set_volume" -> "Adjusting volume"
            "open_app" -> "Opening app"
            else -> "Working on a task"
        }
        val (activity, label) = when (attempt.state) {
            ToolTaskState.WAITING_APPROVAL -> WispActivity.APPROVAL to "Waiting for your approval"
            ToolTaskState.WAITING_INPUT -> WispActivity.APPROVAL to "Waiting for your answer"
            ToolTaskState.UNKNOWN_OUTCOME -> WispActivity.ERROR to "Check the task outcome"
            ToolTaskState.FAILED -> WispActivity.ERROR to "Task could not complete"
            ToolTaskState.CANCELLED -> WispActivity.PAUSED to "Task cancelled"
            ToolTaskState.PAUSED -> WispActivity.PAUSED to "Task paused"
            ToolTaskState.QUEUED, ToolTaskState.READY -> WispActivity.THINKING to "Task queued"
            ToolTaskState.WAITING_RESOURCE -> WispActivity.PAUSED to "Waiting for a resource"
            ToolTaskState.SUCCEEDED -> if (attempt.resultOutcome == ExecutionResult.Outcome.SUCCEEDED)
                WispActivity.SUCCESS to "Task complete" else WispActivity.ERROR to "Check the task outcome"
            ToolTaskState.RUNNING -> when (attempt.request.name) {
                "read_battery" -> WispActivity.CHECKING to description
                "set_volume" -> WispActivity.EDITING to description
                "open_app" -> WispActivity.CONNECTING to description
                else -> WispActivity.THINKING to description
            }
        }
        return WispPresentation(activity, label, attempt.result ?: description, key)
    }

    private val pendingStates = setOf(ToolTaskState.QUEUED, ToolTaskState.READY,
        ToolTaskState.WAITING_INPUT, ToolTaskState.WAITING_RESOURCE, ToolTaskState.PAUSED)
}

/** Only newly observed terminal receipts celebrate; opening history/recreating UI never does. */
internal class WispReceiptTracker {
    private var conversation: String? = null
    private var previous: Map<String, Pair<Long, ToolTaskState>>? = null

    fun update(journal: ToolTaskJournal?, conversationId: String): WispPresentation? {
        if (journal == null) return null
        val attempts = WispPresenter.relevantAttempts(journal, conversationId)
        val baseline = previous.takeIf { conversation == conversationId }
        previous = attempts.associate { it.id to (it.generation to it.state) }
        conversation = conversationId
        if (baseline == null) return null
        return attempts.lastOrNull { attempt ->
            attempt.state in terminalStates && baseline[attempt.id] != (attempt.generation to attempt.state)
        }?.let(WispPresenter::task)
    }

    private val terminalStates = setOf(ToolTaskState.SUCCEEDED, ToolTaskState.FAILED,
        ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
}
