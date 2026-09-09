package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionControllerTest {
    @Test fun lateModelLoadCannotReviveAnEndedOrReplacementCall() {
        val controller = VoiceSessionController(MemoryStore())
        val first = controller.beginCall()
        assertTrue(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        controller.end()
        assertFalse(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
        val second = controller.beginCall()
        assertFalse(controller.setStateIfCurrent(first.id, VoiceSessionState.ACTIVELY_LISTENING))
        assertEquals(second.id, controller.currentCallId())
    }

    @Test
    fun immediateNewCallCanRecallPreviousReplyWithoutResumingTask() {
        val store = MemoryStore()
        var now = 1000L
        val controller = VoiceSessionController(store) { now }
        controller.beginCall()
        controller.appendTranscript("You", "Tell me a moon story")
        controller.appendTranscript("Jarvis", "The astronauts discovered a hidden laboratory.")
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.INTERRUPTED))
        controller.end()
        now += 1000
        val next = controller.beginCall()
        assertTrue(next.transcript.isEmpty())
        assertEquals(null, next.taskStatus)
        assertEquals("The astronauts discovered a hidden laboratory.", controller.conversationContext().last().text)
        controller.appendTranscript("You", "What did you say?")
        assertEquals(1, controller.currentTranscript().size)
        assertEquals(3, controller.conversationContext().size)
    }

    @Test
    fun oldOrDeletedCallsDoNotBecomeImplicitContext() {
        val store = MemoryStore()
        var now = 1000L
        val controller = VoiceSessionController(store) { now }
        val call = controller.beginCall()
        controller.appendTranscript("Jarvis", "Old answer")
        controller.end()
        now += 16 * 60 * 1000
        controller.beginCall()
        assertTrue(controller.conversationContext().isEmpty())
        controller.end()
        store.delete(call.id)
        controller.beginCall()
        assertTrue(controller.conversationContext().isEmpty())
    }

    @Test
    fun restartedControllerUsesSavedRecentDialogueButNotUnfinishedDrafts() {
        val store = MemoryStore()
        val first = VoiceSessionController(store) { 1000L }
        first.beginCall()
        first.appendTranscript("Jarvis", "Last complete reply")
        first.appendTranscript("Jarvis", "An unfinished draft", complete = false)
        first.end()
        val restarted = VoiceSessionController(store) { 2000L }
        restarted.beginCall()
        assertEquals(listOf("Last complete reply"), restarted.conversationContext().map { it.text })
    }

    private class MemoryStore : VoiceCallStore {
        val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) {
            calls.removeAll { it.id == call.id }
            calls += call
        }
        override fun delete(callId: String) { calls.removeAll { it.id == callId } }
    }

    @Test
    fun interruptedCallPreservesIncompleteTranscriptAndTaskState() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store) { 100L }
        controller.beginCall()
        controller.appendTranscript("You", "Find three options", complete = true)
        controller.appendTranscript("Jarvis", "I found two and am comparing", complete = false)
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.WAITING_FOR_USER, pendingSteps = listOf("Compare shipping")))

        val ended = controller.interrupt()

        assertEquals(VoiceTaskState.INTERRUPTED, ended.taskStatus?.state)
        assertFalse(ended.transcript.last().complete)
        assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
    }

    @Test
    fun finalCompletedTaskRemainsCompletedWhenCallEnds() {
        val store = MemoryStore()
        val controller = VoiceSessionController(store)
        controller.beginCall()
        controller.updateTask(VoiceTaskStatus(VoiceTaskState.COMPLETED))
        val ended = controller.end()
        assertEquals(VoiceTaskState.COMPLETED, ended.taskStatus?.state)
        assertTrue(store.calls.single().endedAtMs != null)
    }
}
