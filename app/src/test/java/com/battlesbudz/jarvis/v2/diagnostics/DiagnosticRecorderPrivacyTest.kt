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
    @Test fun realTruncatedCallerEnvelopesFailClosedEvenWhenMarkerWasLost() {
        val prefs = PrivacyPreferences()
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { 1_791_000_000_000L })
        val full = "tiny " + "benign ".repeat(400) + "password: tiny"
        assertTrue(Privacy.excluded(full))
        val prefix = full.take(1_000)
        listOf("Action\nuser=$prefix\nrequest=open_app result=done",
            "Action turn\nuser=$prefix\nsteps=[] receipts=[]",
            "Automatic factual lookup failed\\nuser=$prefix\\nlookupQuery=$prefix",
            "Reference retry after knowledge-gap draft\nuser=$prefix\nlookupQuery=$prefix",
            "Turn\nuser=$prefix\ncleaned=tiny raw=tiny",
            "Turn failed\nuser=$prefix\nerror=failed",
            "Voice audio fallback: chars=${full.length} text=$prefix").forEach { envelope ->
            recorder.record(envelope); recorder.recordImportant(envelope); recorder.recordSummary(envelope)
        }
        recorder.recordInferencePrompt("mode=text\n--- Exact submitted text begins ---\n$prefix\n--- Exact submitted text ends ---")
        assertFalse(recorder.snapshot().contains("tiny"))
        assertFalse(prefs.durableText().contains("tiny"))
        recorder.record("audio event benign")
        recorder.recordSummary("Inference stage=answer firstTokenMs=12 totalMs=45")
        assertTrue(recorder.snapshot().contains("audio event benign"))
        assertTrue(recorder.snapshot().contains("firstTokenMs=12"))
    }
    @Test fun fullSourceProofExcludesSuffixSecretsAndPreservesRealVoiceEpochPrompt() {
        val prefs = PrivacyPreferences()
        var now = 1_791_000_000_000L
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { now })
        val full = "tiny " + "benign ".repeat(400) + "password: tiny"
        recorder.recordSourceInferencePrompt(full.take(1_000), listOf(DiagnosticRecorder.FullSource(full, now)),
            "turn=voice-id submission=1 model=e4b mode=text audioBytes=0")
        assertFalse(prefs.durableText().contains("tiny"))
        val benign = "Please tell me a story about orchids"
        val metadata = "turn=voice-id submission=2 model=e4b mode=text audioBytes=0 promptChars=${benign.length}"
        recorder.recordSourceInferencePrompt(benign, listOf(DiagnosticRecorder.FullSource(benign, now)), metadata)
        assertTrue(recorder.snapshot().contains(benign))
        assertTrue(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains(benign))
        // Source text that looks like metadata receives no exemption from the detector.
        val shaped = "callStartedAtMs=$now oldestEntryAtMs=$now"
        recorder.recordSourceInferencePrompt(shaped, listOf(DiagnosticRecorder.FullSource(shaped, now)), metadata)
        assertFalse(recorder.snapshot().contains(shaped))
        now += Privacy.RETENTION_MS
        assertFalse(recorder.snapshot().contains(benign))
    }

}
