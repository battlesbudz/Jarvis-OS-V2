package com.battlesbudz.jarvis.v2.chat

import com.battlesbudz.jarvis.v2.memory.PrivacyPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import com.battlesbudz.jarvis.v2.voice.*
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryPrivacyTest {
    @Test fun secretSourceAndActionPayloadExcludeWholeMessageBeforePersistence() {
        val prefs = PrivacyPreferences()
        val history = ConversationHistory(prefs.preferences, { 1_000L })
        val id = history.appendUser("harmless ".repeat(400) + " password: tiny")
        assertEquals(id, history.current.value.messages.single().id)
        assertEquals(Privacy.EXCLUDED, history.current.value.messages.single().text)
        assertTrue(history.context().isEmpty())
        val thread = history.current.value.id
        history.updateReply(thread, "r", "draft", false)
        history.recordReplyAction(thread, "r", ActionReceipt("open_app", "access code: 1234", true))
        val receipt = ConversationHistory(prefs.preferences, { 1_000L }).current.value.messages.last().actions.single()
        assertEquals("open_app", receipt.name)
        assertTrue(receipt.succeeded)
        assertEquals(Privacy.EXCLUDED, receipt.message)
        assertFalse(prefs.durableText().contains("tiny"))
        assertFalse(prefs.durableText().contains("1234"))
    }
    @Test fun readsWritesAndLateCallSyncUseOriginalExpiryWithoutWorker() {
        val prefs = PrivacyPreferences()
        var now = 1_000L
        val history = ConversationHistory(prefs.preferences, { now })
        val thread = history.current.value.id
        history.appendUser("Amber notebooks")
        val call = VoiceCallRecord("c", now, conversationId = thread,
            transcript = listOf(TranscriptEntry("You", "Benign call topic", timestampMs = now)))
        history.syncCall(call)
        now += Privacy.RETENTION_MS - 1
        assertEquals(2, ConversationHistory(prefs.preferences, { now }).context().size)
        now++
        assertTrue(history.context().isEmpty())
        assertTrue(history.list().single().messages.all { it.text == Privacy.EXPIRED })
        history.syncCall(call.copy(transcript = call.transcript.map { it.copy(timestampMs = now) }))
        history.updateReply(thread, "r", "New current reply", true)
        val reopened = ConversationHistory(prefs.preferences, { now })
        assertEquals(listOf("New current reply"), reopened.context().map { it.text })
        assertFalse(prefs.durableText().contains("Benign call topic"))
        assertFalse(prefs.durableText().contains("Amber notebooks"))
    }
    @Test fun legacyMissingSourceTimeCannotReceiveNewClock() {
        val prefs = PrivacyPreferences()
        prefs.preferences.edit().putString("threads", """[{"id":"t","messages":[{"id":"m","role":"You","text":"Legacy fact"}]}]""").apply()
        val history = ConversationHistory(prefs.preferences, { 1_000L })
        assertEquals(Privacy.EXPIRED, history.current.value.messages.single().text)
        assertTrue(history.contextAfterMemoryCutoff().isEmpty())
        assertFalse(prefs.durableText().contains("Legacy fact"))
    }
}
