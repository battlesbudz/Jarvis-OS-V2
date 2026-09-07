package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceTurnCoordinatorTest {
    private class MemoryStore : VoiceCallStore {
        val calls = mutableListOf<VoiceCallRecord>()
        override fun list() = calls.toList()
        override fun save(call: VoiceCallRecord) {
            calls.removeAll { it.id == call.id }
            calls += call
        }
        override fun delete(callId: String) {
            calls.removeAll { it.id == callId }
        }
    }

    @Test
    fun streamedResponseIsCheckpointedAndCompleted() = runBlocking {
        val store = MemoryStore()
        val coordinator = VoiceTurnCoordinator(VoiceSessionController(store))
        coordinator.processTurn("What is my battery?",) { emit ->
            emit("Your battery ")
            emit("is at 87 percent.")
            "done"
        }
        val call = store.calls.single()
        assertEquals("Your battery is at 87 percent.", call.transcript.last().text)
        assertEquals(true, call.transcript.last().complete)
    }

    @Test
    fun failedStreamRemainsIncompleteAndIsInterrupted() = runBlocking {
        val store = MemoryStore()
        val session = VoiceSessionController(store)
        val coordinator = VoiceTurnCoordinator(session)
        session.beginCall()
        session.updateTask(VoiceTaskStatus(VoiceTaskState.WAITING_FOR_USER))
        runCatching {
            coordinator.processTurn(null) { emit ->
                emit("I am checking")
                error("model stopped")
            }
        }
        val call = store.calls.single()
        assertFalse(call.transcript.last().complete)
        assertEquals(VoiceTaskState.INTERRUPTED, call.taskStatus?.state)
    }
}
