package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.voice.VoicePhase
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import org.junit.Assert.*
import org.junit.Test

class WispPresentationTest {
    private fun attempt(state: ToolTaskState, name: String = "read_battery", generation: Long = 0,
        outcome: ExecutionResult.Outcome? = null, group: String? = null) = ToolTaskAttempt(
        "task", generation, state, ActionRequest(name, emptyMap()), 1, 2,
        resultOutcome = outcome, groupId = group)
    private fun present(journal: ToolTaskJournal? = null, armed: Boolean = false,
        phase: VoicePhase = VoicePhase.IDLE, busy: Boolean = false, paused: Boolean = false,
        receipt: WispPresentation? = null, observed: WispPresentation? = null,
        callState: VoiceSessionState = VoiceSessionState.PASSIVE_LISTENING) = WispPresenter.present(
        "chat", journal, null, busy, armed, phase, callState, paused, observed, receipt)

    @Test fun callViewportHasModestTargetsAndPreservesShortWindowSpace() {
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(false, VoiceSessionState.ENDED, false))
        assertEquals(WispViewport(204, 128), WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, false))
        assertEquals(WispViewport(116, 66), WispPresenter.viewport(false, VoiceSessionState.ENDED, true))
        assertEquals(WispViewport(138, 80), WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, true))
    }

    @Test fun everyActiveCallStateKeepsItsSpaceUntilEndOrPassiveWakeListening() {
        val activeStates = listOf(VoiceSessionState.ACTIVELY_LISTENING, VoiceSessionState.PROCESSING,
            VoiceSessionState.EXECUTING_ACTION, VoiceSessionState.SPEAKING,
            VoiceSessionState.WAITING_FOR_CONFIRMATION, VoiceSessionState.INTERRUPTED)
        for (compact in listOf(false, true)) {
            val expanded = if (compact) WispViewport(138, 80) else WispViewport(204, 128)
            val resting = if (compact) WispViewport(116, 66) else WispViewport(168, 104)
            for (state in activeStates) {
                assertEquals(state.name, expanded, WispPresenter.viewport(true, state, compact))
                // A stale DTO cannot enlarge the character after explicit disarm/end.
                assertEquals(state.name, resting, WispPresenter.viewport(false, state, compact))
            }
            assertEquals(resting, WispPresenter.viewport(true, VoiceSessionState.ENDED, compact))
            assertEquals(resting, WispPresenter.viewport(true, VoiceSessionState.PASSIVE_LISTENING, compact))
            // A later call can expand again after the previous one ended.
            assertEquals(expanded, WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, compact))
        }
    }

    @Test fun microphonePauseInterruptionAndTaskPosesDoNotCollapseAnActiveCall() {
        val expected = WispViewport(204, 128)
        assertEquals(WispActivity.PAUSED, present(armed = true, phase = VoicePhase.PAUSED,
            paused = true, callState = VoiceSessionState.ACTIVELY_LISTENING).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.ACTIVELY_LISTENING, false))
        assertEquals(WispActivity.PAUSED, present(armed = true,
            callState = VoiceSessionState.INTERRUPTED).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.INTERRUPTED, false))
        val running = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING)))
        assertEquals(WispActivity.CHECKING, present(running, armed = true,
            callState = VoiceSessionState.EXECUTING_ACTION).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.EXECUTING_ACTION, false))
        val approval = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.WAITING_APPROVAL)))
        assertEquals(WispActivity.APPROVAL, present(approval, armed = true,
            callState = VoiceSessionState.WAITING_FOR_CONFIRMATION).activity)
        assertEquals(expected, WispPresenter.viewport(true, VoiceSessionState.WAITING_FOR_CONFIRMATION, false))
        // The same task/wake pose outside an active call retains the resting viewport.
        assertEquals(WispActivity.CHECKING, present(running).activity)
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(false, VoiceSessionState.ENDED, false))
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.WAKE).activity)
        assertEquals(WispViewport(168, 104), WispPresenter.viewport(true, VoiceSessionState.PASSIVE_LISTENING, false))
    }

    @Test fun idleIsPresentAndStaleCallPhaseCannotAnimateAfterEnd() {
        assertEquals(WispActivity.READY, present().activity)
        assertEquals(WispActivity.READY, present(phase = VoicePhase.SPEAKING).activity)
        assertEquals(WispActivity.THINKING, present(busy = true).activity)
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.LISTENING).activity)
        assertEquals(WispActivity.SPEAKING, present(armed = true, phase = VoicePhase.SPEAKING).activity)
        assertEquals(WispActivity.PAUSED, present(armed = true, phase = VoicePhase.LISTENING, paused = true).activity)
    }

    @Test fun audioComesFromTheActualOwnerAndNonFiniteLevelsAreSilent() {
        fun level(activity: WispActivity, phase: VoicePhase, armed: Boolean = true, paused: Boolean = false,
            mic: Float = .2f, output: Float = .8f) = WispPresenter.audioLevel(activity, phase, armed, paused, mic, output)
        assertEquals(.2f, level(WispActivity.LISTENING, VoicePhase.LISTENING), 0f)
        assertEquals(.8f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING), 0f)
        assertEquals(.8f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, paused = true), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, paused = true), 0f)
        assertEquals(0f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, armed = false), 0f)
        assertEquals(0f, level(WispActivity.THINKING, VoicePhase.SPEAKING), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, mic = Float.NaN), 0f)
        assertEquals(0f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, output = Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, level(WispActivity.SPEAKING, VoicePhase.SPEAKING, output = 3f), 0f)
        assertEquals(0f, level(WispActivity.LISTENING, VoicePhase.LISTENING, mic = -1f), 0f)
    }

    @Test fun everyRunningPropRequiresItsActualTool() {
        for ((tool, expected) in mapOf("read_battery" to WispActivity.CHECKING,
            "set_volume" to WispActivity.EDITING, "open_app" to WispActivity.CONNECTING,
            "unknown_tool" to WispActivity.THINKING)) {
            val journal = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING, tool)))
            assertEquals(tool, expected, present(journal).activity)
        }
        assertEquals(WispActivity.THINKING, present(ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.QUEUED)))).activity)
    }

    @Test fun approvalAndUnknownOutcomesTakePriorityWithoutStartingEffects() {
        val approval = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.WAITING_APPROVAL)))
        assertEquals(WispActivity.APPROVAL, present(approval, armed = true, phase = VoicePhase.SPEAKING).activity)
        assertEquals(ToolTaskState.WAITING_APPROVAL, approval.attempts.single().state)
        val unknown = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.UNKNOWN_OUTCOME)))
        assertEquals(WispActivity.ERROR, present(unknown, busy = true).activity)
        assertEquals(WispActivity.READY, present(unknown.copy(attempts = listOf(unknown.attempts.single().copy(reconciled = true)))).activity)
    }

    @Test fun anotherConversationCannotDriveTheCharacter() {
        val journal = ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING, group = "foreign")),
            groups = listOf(ToolTaskGroup("foreign", "other-chat", "Battery", listOf("task"), 1, 10)))
        assertEquals(WispActivity.READY, present(journal).activity)
    }

    @Test fun currentReferenceObservationOverridesThinkingButNeverCreatesSuccess() {
        val reference = WispPresentation(WispActivity.CHECKING, "Checking references")
        assertEquals(reference, present(busy = true, observed = reference))
        assertEquals(WispActivity.THINKING, present(busy = true, observed = null).activity)
        assertEquals(WispActivity.READY, present(observed = null).activity)
    }

    @Test fun recentTerminalFailureNeverOverridesFreshConversationOrAudioWork() {
        val error = WispPresentation(WispActivity.ERROR, "Something went wrong")
        assertEquals(WispActivity.ERROR, present(observed = error).activity)
        assertEquals(WispActivity.THINKING, present(busy = true, observed = error).activity)
        assertEquals(WispActivity.LISTENING, present(armed = true, phase = VoicePhase.LISTENING, observed = error).activity)
        assertEquals(WispActivity.SPEAKING, present(armed = true, phase = VoicePhase.SPEAKING, observed = error).activity)
    }

    @Test fun successRequiresAnExecutorReceiptAndNeverPlaysOnHistoricalLoad() {
        val tracker = WispReceiptTracker()
        val completed = attempt(ToolTaskState.SUCCEEDED, generation = 1, outcome = ExecutionResult.Outcome.SUCCEEDED)
        assertNull(tracker.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        assertNull(tracker.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        val fresh = WispReceiptTracker()
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(attempt(ToolTaskState.RUNNING))), "chat"))
        assertEquals(WispActivity.SUCCESS, fresh.update(ToolTaskJournal(attempts = listOf(completed)), "chat")?.activity)
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(completed)), "chat"))
        assertNull(fresh.update(ToolTaskJournal(attempts = listOf(completed)), "different-chat"))
        assertEquals(WispActivity.ERROR, WispPresenter.task(completed.copy(resultOutcome = null)).activity)
        assertEquals(WispActivity.ERROR, WispPresenter.task(completed.copy(resultOutcome = ExecutionResult.Outcome.UNKNOWN_COMPLETION)).activity)
    }

    @Test fun quickTasksBetweenFramesAreObservedAndFailureCancellationNeverCelebrate() {
        for ((state, expected) in mapOf(ToolTaskState.FAILED to WispActivity.ERROR,
            ToolTaskState.UNKNOWN_OUTCOME to WispActivity.ERROR, ToolTaskState.CANCELLED to WispActivity.PAUSED)) {
            val tracker = WispReceiptTracker()
            assertNull(tracker.update(ToolTaskJournal(), "chat"))
            assertEquals(expected, tracker.update(ToolTaskJournal(attempts = listOf(attempt(state))), "chat")?.activity)
        }
    }
}
