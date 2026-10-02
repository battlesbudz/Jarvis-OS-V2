package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.memory.PrivacyPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderPrivacyTest {
    @Test fun completeSecretScanPrecedesEveryChannelTruncation() {
        val prefs = PrivacyPreferences()
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { 1_000L })
        val secret = "harmless ".repeat(400) + " password: tiny"
        recorder.startSession(secret)
        recorder.record(secret)
        recorder.recordImportant(secret)
        recorder.recordSummary(secret)
        recorder.recordInferencePrompt(secret)
        recorder.recordTurnEvidence("turn", "recognition", secret)
        recorder.recordTurnEvidence("access code: 1234", "category", "benign")
        assertFalse(recorder.snapshot().contains("tiny"))
        assertFalse(prefs.durableText().contains("tiny"))
        assertFalse(prefs.durableText().contains("1234"))
        assertFalse(DiagnosticRecorder(prefs.preferences, clock = { 1_000L }).apply { restore() }.snapshot().contains("tiny"))
    }
    @Test fun exactBenignPromptExpiresAtOriginalClockAndRetryCannotRenewIt() {
        val prefs = PrivacyPreferences()
        var now = 1_791_000_000_000L
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { now })
        val prompt = "full benign prompt ".repeat(400) + "FINAL CURRENT QUESTION"
        recorder.recordInferencePrompt(prompt)
        recorder.record("amber event")
        recorder.recordImportant("amber important")
        recorder.recordSummary("amber summary")
        recorder.recordTurnEvidence("turn", "category", "amber evidence")
        now += Privacy.RETENTION_MS - 1
        assertTrue(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains(prompt))
        now++
        assertFalse(recorder.snapshot().contains(prompt))
        recorder.recordInferencePrompt(prompt)
        val restored = DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }
        assertFalse(restored.snapshot().contains(prompt))
        assertFalse(prefs.durableText().contains("amber"))
    }
    @Test fun unknownLegacyDiagnosticTimesAreNotRebased() {
        val prefs = PrivacyPreferences()
        prefs.preferences.edit().putString("diagnostics", "[\"Legacy raw event\"]")
            .putString("diagnostics_inference_prompts", "[\"Legacy raw prompt\"]").apply()
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { 1_000L })
        recorder.restore()
        assertFalse(recorder.snapshot().contains("Legacy raw"))
        assertFalse(prefs.durableText().contains("Legacy raw"))
    }
}
