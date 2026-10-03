package com.battlesbudz.jarvis.v2.diagnostics

import com.battlesbudz.jarvis.v2.memory.PrivacyPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderPrivacyTest {
    @Test fun eachFullSourceClockMustBeEligibleBeforeSelectingOldestCapture() {
        val now = 1_791_000_000_000L
        listOf(0L, -1L, now + 1, Long.MAX_VALUE, now - Privacy.RETENTION_MS).forEach { invalid ->
            val prefs = PrivacyPreferences()
            val recorder = DiagnosticRecorder(prefs.preferences, clock = { now }, sourceTimestamp = { now - 10 })
            val text = "Invalid capture must stay absent"
            recorder.recordSourceInferencePrompt(text, listOf(
                DiagnosticRecorder.FullSource("Valid source", now - 20),
                DiagnosticRecorder.FullSource(text, invalid)))
            assertFalse("invalid=$invalid", recorder.snapshot().contains(text))
            assertFalse(prefs.durableText().contains(text))
            // A retry cannot turn the rejected provenance into a fresh source clock.
            recorder.recordSourceInferencePrompt(text, listOf(DiagnosticRecorder.FullSource(text, now)))
            assertFalse(recorder.snapshot().contains(text))
            assertFalse(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains(text))
        }
        val prefs = PrivacyPreferences()
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { now }, sourceTimestamp = { now - 10 })
        val text = "Eligible sources retain exact text"
        recorder.recordSourceInferencePrompt(text, listOf(DiagnosticRecorder.FullSource(text, now - 20)))
        assertTrue(recorder.snapshot().contains("atMs=${now - 20}"))
        assertTrue(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains(text))
        listOf(0L, now + 1, Long.MAX_VALUE, now - Privacy.RETENTION_MS).forEach { invalidHistory ->
            val invalidPrefs = PrivacyPreferences()
            val invalidRecorder = DiagnosticRecorder(invalidPrefs.preferences, clock = { now }, sourceTimestamp = { invalidHistory })
            invalidRecorder.recordSourceInferencePrompt(text, listOf(DiagnosticRecorder.FullSource(text, now)))
            assertFalse(invalidPrefs.durableText().contains(text))
        }
    }

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
        assertFalse(recorder.snapshot().contains("tiny"))
        assertFalse(prefs.durableText().contains("tiny"))
        val metrics = "Inference\nstage=answer\npromptChars=12\ntimeToFirstTokenMs=45\nnativeSubmitMs=2 firstCallbackMs=3\ntotalGenerationTimeMs=50\noutputTokensEstimated=10\nstreamEvents=4\ndecodeTokensPerSecondEstimated=20.5"
        recorder.recordSummary(metrics)
        recorder.recordTurnEvidence("turn", "benchmark", metrics)
        recorder.recordSummary(metrics.replace("nativeSubmitMs=2 firstCallbackMs=3", "nativeSubmitMs=null firstCallbackMs=null"))
        assertTrue(recorder.snapshot().contains(metrics))
        assertTrue(DiagnosticRecorder(prefs.preferences, clock = { 1_791_000_000_000L }).apply { restore() }.snapshot().contains(metrics))
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

    @Test fun completeTypedProductionCallbackRetainsExactMarkersThroughRestoreAndExpiry() {
        val prefs = PrivacyPreferences()
        var now = 1_791_000_000_000L
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { now })
        val exact = "turn=reply-id submission=1 mode=text recordedByBuild=test model=e4b audioBytes=0 promptChars=35 historyEntries=2 capture=stored\n" +
            "--- Exact submitted text begins ---\nTell me a story about orchids\n--- Exact submitted text ends ---"
        recorder.recordInferencePrompt(exact)
        assertTrue(recorder.snapshot().contains(exact))
        assertTrue(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains(exact))
        val sourceShaped = "--- Exact submitted text begins ---\natMs=$now\n--- Exact submitted text ends ---"
        recorder.recordInferencePrompt(sourceShaped)
        assertFalse(recorder.snapshot().contains(sourceShaped))
        now += Privacy.RETENTION_MS
        assertFalse(recorder.snapshot().contains(exact))
    }
    @Test fun everyUnprovenancedFreeformChannelExcludesMessageAndColonExceptionExcerpts() {
        val prefs = PrivacyPreferences()
        val now = 1_791_000_000_000L
        val full = "tiny " + "benign ".repeat(800) + "password: tiny"
        assertTrue(Privacy.excluded(full))
        val recorder = DiagnosticRecorder(prefs.preferences, clock = { now })
        listOf("Voice incremental fallback: message=${full.take(300)}",
            "Voice turn failed: ${full.take(4_000)}",
            "Voice resume failed: ${full.take(4_000)}",
            "arbitrary label ${full.take(500)}").forEach { excerpt ->
            recorder.record(excerpt); recorder.recordImportant(excerpt); recorder.recordSummary(excerpt)
            recorder.recordTurnEvidence("turn", "failure", excerpt)
        }
        assertFalse(recorder.snapshot().contains("tiny"))
        assertFalse(prefs.durableText().contains("tiny"))
        assertFalse(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains("tiny"))
        // A legacy persisted row with an eligible-looking header has no recorder-issued proof.
        prefs.preferences.edit().putString("diagnostics", org.json.JSONArray().put("atMs=$now\nVoice turn failed: ${full.take(4_000)}").toString()).apply()
        assertFalse(DiagnosticRecorder(prefs.preferences, clock = { now }).apply { restore() }.snapshot().contains("tiny"))
    }

}
