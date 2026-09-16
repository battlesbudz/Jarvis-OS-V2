package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TtsComparisonStoreTest {
    @Test fun retiredSelectionsMigrateWithoutChangingSavedPiperTuningOrHistory() {
        for (retired in listOf("kokoro", "pocket_paul")) {
            val prefs = preferences()
            val history = """[{"engine":"$retired","atMs":1,"model":"historical"}]"""
            prefs.edit().putString("engine", retired).putString("results", history)
                .putString("call_profile_$retired", "threads-2-native-stream-speed-0.9")
                .putString("call_profile_piper_northern_english_male_medium", "threads-2-opening-160-speed-0.9").apply()
            val store = TtsComparisonStore(prefs)
            assertEquals(TtsEngine.PIPER_NORTHERN, store.selectedEngine())
            assertEquals(TtsEngine.PIPER_NORTHERN.id, prefs.getString("engine", null))
            assertEquals(TtsBenchmarkProfile(2, 160, 0.9f), store.callProfile(TtsEngine.PIPER_NORTHERN))
            assertEquals(history, prefs.getString("results", null))
            assertTrue(store.snapshot().contains(TtsEngine.diagnosticLabel(retired)))
        }
    }

    @Test fun kokoroSourceAndPlaybackEvidenceSurvivesSavingAndWholeSuiteExport() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        val metrics = TtsSessionMetrics(10, 20, 50, 80, 0, 1f, 0, 1, 1, 3, "hash", 2, true, null,
            observedPlaybackStarvationMs = 0, sourcePcmSummary = "event=source_summary nearSilentFrames=2400",
            pcmDelivery = "kokoro_sentence_callbacks_v1")
        store.add(TtsEngine.PIPER_NORTHERN, "test", "short", metrics,
            TtsBenchmarkRun("kokoro-suite", TtsBenchmarkProfile(), 1, "Hello.", 0, 0,
                audioFile = "kokoro-suite-1.wav"))
        val restored = TtsComparisonStore(prefs)
        val report = restored.suiteDiagnosticReport(restored.records().single())
        assertTrue(report.contains("observed_playback_starvation_ms=0"))
        assertTrue(report.contains("source_pcm_summary=event=source_summary nearSilentFrames=2400"))
        assertTrue(report.contains("pcm_delivery=kokoro_sentence_callbacks_v1"))
    }

    @Test fun wholeSuiteSurvivesRestartOrdersRunsAndKeepsFailuresWithoutMixingSuites() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        val profile = TtsBenchmarkProfile(2, null, 1f)
        val metrics = TtsSessionMetrics(10, 20, 50, 80, 0, 1f, 0, 0, 1, 3, "hash", 2, true, null)
        // Deliberately save out of order: the original execution number wins over storage order.
        for (number in listOf(14, 5, 10, 1)) {
            store.add(TtsEngine.PIPER_NORTHERN, "paul-isolation-v1", "case-$number",
                metrics.copy(completed = number != 10, error = if (number == 10) "stopped" else null),
                TtsBenchmarkRun("suite-a", profile, if (number > 7) 2 else 1, "Text $number", 0, 3,
                    audioFile = "suite-a-$number.wav"))
        }
        val selected = store.records().first()
        store.add(TtsEngine.PIPER_NORTHERN, "paul-isolation-v1", "unrelated", metrics,
            TtsBenchmarkRun("suite-b", profile, 1, "Unrelated", 0, 0))
        val restored = TtsComparisonStore(prefs)
        val report = restored.suiteDiagnosticReport(selected)
        assertTrue(report.contains("saved_runs=4"))
        assertTrue(report.contains("expected_runs=14"))
        assertTrue(report.contains("all_runs_retained=false"))
        assertTrue(report.contains("completed_runs=3"))
        assertTrue(report.contains("error=stopped"))
        assertTrue(report.indexOf("===== RUN 5") < report.indexOf("===== RUN 10"))
        assertTrue(report.indexOf("===== RUN 10") < report.indexOf("===== RUN 14"))
        assertFalse(report.contains("suite-b"))
        assertFalse(report.contains("Unrelated"))
    }


    @Test fun isolatedRunExportsItsOwnDeliveryAndProvenanceWithoutChangingCallProfile() {
        val store = TtsComparisonStore(preferences())
        val profile = TtsBenchmarkProfile(2, null, 1f)
        store.setCallProfile(TtsEngine.PIPER_NORTHERN, profile)
        val metrics = TtsSessionMetrics(10, 20, 50, 80, 0, 1f, 0, 0, 2, 3, "hash", 2, true, null)
        store.add(TtsEngine.PIPER_NORTHERN, "paul-isolation-v1", "case", metrics,
            TtsBenchmarkRun("suite", profile.copy(threads = 4), 1, "A. B.", 0, 4,
                inputDelivery = "all text upfront after model ready", submissions = listOf("A.", "B."),
                provenance = mapOf("versionCode" to "123", "ciSourceCommit" to "commit")))
        val saved = store.records().single()
        assertEquals("all text upfront after model ready", saved.getString("input_delivery"))
        assertEquals(2, saved.getJSONArray("planned_submissions").length())
        assertEquals("123", saved.getJSONObject("provenance").getString("versionCode"))
        assertTrue(saved.isNull("first_intelligible_word_ms"))
        assertEquals("not_assessed", saved.getString("intelligibility_assessment"))
        assertTrue(saved.getBoolean("thermal_limited"))
        assertEquals(profile, store.callProfile(TtsEngine.PIPER_NORTHERN))
        assertTrue(TtsComparisonStore.describe(saved).contains("first_intelligible_word_ms=unavailable"))
    }



    @Test fun everyApplicableProfileRoundTripsAndUnknownSettingsFallBackToDefaults() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        for (engine in TtsEngine.entries) {
            for (profile in TtsBenchmarkProfile.selectableProfiles) {
                if (profile.piperPassages && engine != TtsEngine.PIPER_NORTHERN) continue
                store.setCallProfile(engine, profile)
                assertEquals(profile, TtsComparisonStore(prefs).callProfile(engine))
            }
            prefs.edit().putString("call_profile_${engine.id}", "unknown-profile").apply()
            assertNull(store.callProfile(engine))
        }
    }



    @Test fun miroSelectionRetiresWithoutRelabelingItsMeasurements() {
        assertEquals(TtsEngine.PIPER_NORTHERN, TtsEngine.fromId("piper_miro_high"))
        assertEquals("Piper Miro High (British) (retired)", TtsEngine.diagnosticLabel("piper_miro_high"))
        assertFalse(TtsEngine.entries.any { it.id == "piper_miro_high" })
    }

    @Test fun copyIsBoundToOneTextRunAndSurvivesNewRunsAndVoiceChanges() {
        val store = TtsComparisonStore(preferences())
        val profile = TtsBenchmarkProfile(playbackSpeed = 0.9f, openingChars = 60)
        val metrics = TtsSessionMetrics(100, 200, 1100, 1000, 0, 0.9f, 0, 1, 1, 5, "abc", 4, true, null)
        store.add(TtsEngine.PIPER_NORTHERN, "voice-profiles-v3", "short-v1", metrics,
            TtsBenchmarkRun("suite-a", profile, 1, "First text.", 0, 0))
        val displayed = store.records().single()
        val original = TtsComparisonStore.diagnosticReport(displayed)
        store.select(TtsEngine.PIPER_NORTHERN)
        store.add(TtsEngine.PIPER_NORTHERN, "voice-profiles-v3", "story-v1", metrics,
            TtsBenchmarkRun("suite-b", profile, 2, "Unrelated later text.", 0, 4))
        assertEquals(original, TtsComparisonStore.diagnosticReport(displayed))
        assertTrue(original.contains("suite_id=suite-a"))
        assertTrue(original.contains("profile_id=${profile.id}"))
        assertTrue(original.contains("input_text=First text."))
        assertFalse(original.contains("Unrelated"))
        assertFalse(original.contains("suite-b"))
        assertEquals(0.99, displayed.getDouble("effective_rtf"), 0.0001)
        assertTrue(displayed.getBoolean("playback_speed_applied"))
        assertTrue(store.records().last().getBoolean("thermal_limited"))
        assertNotEquals(displayed.getString("id"), store.records().last().getString("id"))
    }

    @Test fun fullComparisonIsNotEvictedByTheFortyCallHistoryLimit() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 0, 1f, 0, 1, 1, 50, "abc", 4, true, null)
        repeat(TtsBenchmarkProfile.comparisonRunCount) {
            store.add(TtsEngine.PIPER_NORTHERN, "voice-profiles-v3", "short-v1", metrics,
                TtsBenchmarkRun("complete-suite", TtsBenchmarkProfile(), 1, "Text $it", 0, 0))
        }
        repeat(45) { store.add(TtsEngine.PIPER_NORTHERN, "voice-call", "$it", metrics) }
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsBenchmarkProfile.comparisonRunCount, restored.records().count { it.optString("suite_id") == "complete-suite" })
        assertEquals(40, restored.records().count { it.optString("source") == "voice-call" })
    }

    @Test fun unsupportedSlowPlaybackIsMarkedInsteadOfClaimingPointNineWorked() {
        val store = TtsComparisonStore(preferences())
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 0, 1f, 0, 1, 1, 50, "abc", 4, true, null)
        store.add(TtsEngine.PIPER_NORTHERN, "voice-profiles-v3", "short-v1", metrics,
            TtsBenchmarkRun("suite", TtsBenchmarkProfile(openingChars = null, playbackSpeed = 0.9f), 1, "Text", 0, 0))
        val record = store.records().single()
        assertFalse(record.getBoolean("playback_speed_applied"))
        assertTrue(record.isNull("opening_target_chars"))
        assertEquals("full-text-before-playback", record.getString("synthesis_mode"))
    }

    @Test fun selectionsAndResultsSurviveRestartWithoutMixingModels() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        store.select(TtsEngine.PIPER_NORTHERN)
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 20, 1f, 0, 1, 2, 50, "abc", 4, true, null)
        store.add(TtsEngine.PIPER_NORTHERN, "benchmark-v1", "short-v1", metrics)
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.PIPER_NORTHERN, restored.selectedEngine())
        val entry = restored.records().single()
        assertEquals(TtsEngine.PIPER_NORTHERN.id, entry.getString("engine"))
        assertEquals(0.2, entry.getDouble("rtf"), 0.0001)
        assertTrue(entry.getBoolean("completed"))
        repeat(45) { restored.add(TtsEngine.PIPER_NORTHERN, "voice-call", "$it", metrics.copy(completed = false, error = "stopped")) }
        assertEquals(40, restored.records().size)
        assertFalse(restored.records().last().getBoolean("completed"))
        assertTrue(restored.snapshot().contains("error=stopped"))
    }
    @Test fun retiredSelectionFallsBackButHistoricalIdentitySurvives() {
        val prefs = preferences()
        prefs.edit().putString("engine", "kokoro_int8")
            .putString("results", """[{"engine":"kokoro_int8","model":"kokoro-int8-en-v0_19","atMs":1}]""").apply()
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.PIPER_NORTHERN, restored.selectedEngine())
        assertFalse(TtsEngine.entries.any { it.id == "kokoro_int8" })
        assertTrue(restored.snapshot().contains("TTS Kokoro INT8 (retired)"))
        assertTrue(restored.snapshot().contains("model=kokoro-int8-en-v0_19"))
        for (id in listOf("piper", "piper_alan", "piper_ryan_high")) {
            prefs.edit().putString("engine", id).apply()
            assertEquals(TtsEngine.PIPER_NORTHERN, restored.selectedEngine())
            assertTrue(TtsEngine.diagnosticLabel(id).endsWith("(retired)"))
            assertFalse(TtsEngine.diagnosticLabel(id).startsWith("Kokoro"))
        }
        assertEquals(setOf(TtsEngine.PIPER_NORTHERN), TtsEngine.entries.toSet())
        assertEquals("future_voice", TtsEngine.diagnosticLabel("future_voice"))
    }

    @Test fun piperPassageProfileSurvivesRestartWithoutChangingOtherVoices() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        val profile = TtsBenchmarkProfile(openingChars = 320)
        store.setCallProfile(TtsEngine.PIPER_NORTHERN, profile)
        assertEquals(profile, TtsComparisonStore(prefs).callProfile(TtsEngine.PIPER_NORTHERN))
        assertFalse(profile.fullText)
        assertTrue(profile.piperPassages)
    }



    @Test fun northernPiperSelectionSurvivesRestart() {
        val prefs = preferences()
        TtsComparisonStore(prefs).select(TtsEngine.PIPER_NORTHERN)
        assertEquals(TtsEngine.PIPER_NORTHERN, TtsComparisonStore(prefs).selectedEngine())
    }



    private fun preferences(): SharedPreferences {
        val data = mutableMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { data[args[0] as String] = args[1] as String?; editor }
                "apply" -> null
                else -> error("Unexpected preferences edit ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> data[args[0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences read ${method.name}")
            }
        } as SharedPreferences
    }
}
