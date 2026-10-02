package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.memory.PrivacyPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import org.junit.Assert.*
import org.junit.Test

class VoiceCallStorePrivacyTest {
    @Test fun transcriptDeliveryTitleAndTasksNeverPersistSecrets() {
        val prefs = PrivacyPreferences()
        val store = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { 1_000L })
        val secret = "x".repeat(2_100) + " Card number 4111 1111 1111 1111"
        store.save(VoiceCallRecord("c", 1_000L, title = "access code: 1234",
            transcript = listOf(TranscriptEntry("Jarvis", "benign", timestampMs = 1_000L, replyId = "r",
                actions = listOf(VoiceActionOutcome("open_app", "password: tiny", true)),
                delivery = SpeechDelivery("r", spans = listOf(DeliveredSpeechSpan(0, secret, 0, 10, 10, true))))),
            taskStatus = VoiceTaskStatus(VoiceTaskState.COMPLETED, completedSteps = listOf("password: tiny"))))
        val saved = store.list().single()
        assertEquals("c", saved.id)
        assertEquals("r", saved.transcript.single().replyId)
        assertEquals("open_app", saved.transcript.single().actions.single().name)
        assertTrue(saved.transcript.single().actions.single().succeeded)
        assertEquals(Privacy.EXCLUDED, saved.title)
        listOf("4111", "tiny", "1234").forEach { assertFalse(prefs.durableText().contains(it)) }
    }
    @Test fun expiredCallCannotResurrectFromCoalescedSnapshotOrRenewedTime() {
        val prefs = PrivacyPreferences()
        var now = 1_000L
        val store = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { now })
        val call = VoiceCallRecord("c", now, title = "Amber notebooks",
            transcript = listOf(TranscriptEntry("You", "Amber notebooks", timestampMs = now)),
            taskStatus = VoiceTaskStatus(VoiceTaskState.COMPLETED, listOf("open_app")))
        store.save(call)
        now += Privacy.RETENTION_MS - 1
        assertEquals("Amber notebooks", SharedPreferencesVoiceCallStore(prefs.preferences, clock = { now }).list().single().title)
        now++
        assertEquals(Privacy.EXPIRED, store.list().single().title)
        store.save(call.copy(startedAtMs = now, transcript = call.transcript.map { it.copy(timestampMs = now) }))
        val restored = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { now }).list().single()
        assertEquals(1_000L, restored.startedAtMs)
        assertEquals(Privacy.EXPIRED, restored.transcript.single().text)
        assertFalse(prefs.durableText().contains("Amber notebooks"))
    }
    @Test fun legacyZeroCaptureFailsClosed() {
        val prefs = PrivacyPreferences()
        val store = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { 1_000L })
        store.save(VoiceCallRecord("legacy", 0, title = "Unknown capture", transcript = listOf(TranscriptEntry("You", "Unknown capture", timestampMs = 0))))
        assertEquals(Privacy.EXPIRED, store.list().single().transcript.single().text)
        assertFalse(prefs.durableText().contains("Unknown capture"))
    }
}
