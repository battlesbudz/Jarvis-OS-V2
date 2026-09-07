package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionControllerTest {
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
