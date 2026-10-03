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

    @Test fun realResumeAndLinkCannotRenewLegacyFreeformTaskSources() {
        val prefs = PrivacyPreferences()
        val captured = 1_791_000_000_000L
        var now = captured
        val legacy = VoiceCallRecord("legacy", captured,
            transcript = listOf(TranscriptEntry("You", "Amber notebooks", timestampMs = captured)),
            taskStatus = VoiceTaskStatus(VoiceTaskState.FAILED,
                completedSteps = listOf("Opened Amber Notebook", "open_app"),
                pendingSteps = listOf("Read the Amber Notebook", "read_battery", "set_volume")))
        // Public legacy codec permits prose tasks; storage must reject their absent source
        // lineage before the real controller copies them into a fresh session.
        prefs.preferences.edit().putString("voice_calls", SharedPreferencesVoiceCallStore.encode(listOf(legacy))).apply()
        now += Privacy.RETENTION_MS - 86_400_000L
        val store = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { now })
        val controller = VoiceSessionController(store, nowMs = { now })
        val resumed = controller.resumeCall(legacy)
        assertNotEquals(legacy.id, resumed.id)
        assertEquals(now, resumed.startedAtMs)
        controller.linkConversation("conversation")
        val linked = store.list().first { it.id == resumed.id }
        assertTrue(linked.transcript.isEmpty())
        assertEquals(VoiceTaskState.FAILED, linked.taskStatus!!.state)
        assertEquals(listOf(Privacy.EXCLUDED, "open_app"), linked.taskStatus!!.completedSteps)
        assertEquals(listOf(Privacy.EXCLUDED, "read_battery", "set_volume"), linked.taskStatus!!.pendingSteps)
        now = captured + Privacy.RETENTION_MS
        val reopened = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { now })
        assertEquals(Privacy.EXPIRED, reopened.list().first { it.id == legacy.id }.transcript.single().text)
        assertEquals(linked.taskStatus, reopened.list().first { it.id == resumed.id }.taskStatus)
        assertFalse(prefs.durableText().contains("Amber"))
        // Delete/reopen/new ID and rewording supply no authority to create a new clock.
        reopened.delete(resumed.id)
        reopened.save(linked.copy(id = "retry", startedAtMs = now,
            taskStatus = linked.taskStatus!!.copy(completedSteps = listOf("Reopened Amber Notebook", "open_app"))))
        assertEquals(listOf(Privacy.EXCLUDED, "open_app"), reopened.list().first { it.id == "retry" }.taskStatus!!.completedSteps)
        assertFalse(prefs.durableText().contains("Amber"))
    }

    @Test fun freshAndRewordedUnprovenancedTasksFailClosedWhileActionIdentitySurvives() {
        val prefs = PrivacyPreferences()
        val store = SharedPreferencesVoiceCallStore(prefs.preferences, clock = { 1_000L })
        val full = "tiny " + "benign ".repeat(400) + "password: tiny"
        store.save(VoiceCallRecord("call", 1_000L, title = "Benign title",
            taskStatus = VoiceTaskStatus(VoiceTaskState.COMPLETED, listOf(full, "open_app"))))
        assertEquals(Privacy.EXCLUDED, store.list().single().title)
        assertEquals(listOf(Privacy.EXCLUDED, "open_app"), store.list().single().taskStatus!!.completedSteps)
        store.delete("call")
        store.save(VoiceCallRecord("call", 1_000L,
            taskStatus = VoiceTaskStatus(VoiceTaskState.COMPLETED, listOf("Opened tiny", "open_app"))))
        assertFalse(prefs.durableText().contains("tiny"))
        prefs.preferences.edit().clear().apply()
        SharedPreferencesVoiceCallStore(prefs.preferences, clock = { 1_000L }).save(VoiceCallRecord("new-call", 1_000L,
            taskStatus = VoiceTaskStatus(VoiceTaskState.COMPLETED, listOf("Opened tiny", "open_app"))))
        assertFalse(prefs.durableText().contains("tiny"))
    }
}
