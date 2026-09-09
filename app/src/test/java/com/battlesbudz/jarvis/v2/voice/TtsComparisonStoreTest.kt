package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TtsComparisonStoreTest {
    @Test fun copyIsBoundToOneTextRunAndSurvivesNewRunsAndVoiceChanges() {
        val store = TtsComparisonStore(preferences())
        val profile = TtsBenchmarkProfile(playbackSpeed = 0.9f, openingChars = 60)
        val metrics = TtsSessionMetrics(100, 200, 1100, 1000, 0, 0.9f, 0, 1, 1, 5, "abc", 4, true, null)
        store.add(TtsEngine.KOKORO, "voice-profiles-v3", "short-v1", metrics,
            TtsBenchmarkRun("suite-a", profile, 1, "First text.", 0, 0))
        val displayed = store.records().single()
        val original = TtsComparisonStore.diagnosticReport(displayed)
        store.select(TtsEngine.PIPER_MIRO)
        store.add(TtsEngine.PIPER_MIRO, "voice-profiles-v3", "story-v1", metrics,
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
            store.add(TtsEngine.KOKORO, "voice-profiles-v3", "short-v1", metrics,
                TtsBenchmarkRun("complete-suite", TtsBenchmarkProfile(), 1, "Text $it", 0, 0))
        }
        repeat(45) { store.add(TtsEngine.PIPER_MIRO, "voice-call", "$it", metrics) }
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsBenchmarkProfile.comparisonRunCount, restored.records().count { it.optString("suite_id") == "complete-suite" })
        assertEquals(40, restored.records().count { it.optString("source") == "voice-call" })
    }

    @Test fun unsupportedSlowPlaybackIsMarkedInsteadOfClaimingPointNineWorked() {
        val store = TtsComparisonStore(preferences())
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 0, 1f, 0, 1, 1, 50, "abc", 4, true, null)
        store.add(TtsEngine.KOKORO, "voice-profiles-v3", "short-v1", metrics,
            TtsBenchmarkRun("suite", TtsBenchmarkProfile(openingChars = null, playbackSpeed = 0.9f), 1, "Text", 0, 0))
        val record = store.records().single()
        assertFalse(record.getBoolean("playback_speed_applied"))
        assertTrue(record.isNull("opening_target_chars"))
        assertEquals("full-text-before-playback", record.getString("synthesis_mode"))
    }

    @Test fun selectionsAndResultsSurviveRestartWithoutMixingModels() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        store.select(TtsEngine.PIPER_MIRO)
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 20, 1f, 0, 1, 2, 50, "abc", 4, true, null)
        store.add(TtsEngine.KOKORO, "benchmark-v1", "short-v1", metrics)
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.PIPER_MIRO, restored.selectedEngine())
        val entry = restored.records().single()
        assertEquals(TtsEngine.KOKORO.id, entry.getString("engine"))
        assertEquals(0.2, entry.getDouble("rtf"), 0.0001)
        assertTrue(entry.getBoolean("completed"))
        repeat(45) { restored.add(TtsEngine.PIPER_MIRO, "voice-call", "$it", metrics.copy(completed = false, error = "stopped")) }
        assertEquals(40, restored.records().size)
        assertFalse(restored.records().last().getBoolean("completed"))
        assertTrue(restored.snapshot().contains("error=stopped"))
    }
    @Test fun retiredSelectionFallsBackButHistoricalIdentitySurvives() {
        val prefs = preferences()
        prefs.edit().putString("engine", "kokoro_int8")
            .putString("results", """[{"engine":"kokoro_int8","model":"kokoro-int8-en-v0_19","atMs":1}]""").apply()
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.KOKORO, restored.selectedEngine())
        assertFalse(TtsEngine.entries.any { it.id == "kokoro_int8" })
        assertTrue(restored.snapshot().contains("TTS Kokoro INT8 (retired)"))
        assertTrue(restored.snapshot().contains("model=kokoro-int8-en-v0_19"))
        for (id in listOf("piper", "piper_alan", "piper_ryan_high")) {
            prefs.edit().putString("engine", id).apply()
            assertEquals(TtsEngine.KOKORO, restored.selectedEngine())
            assertTrue(TtsEngine.diagnosticLabel(id).endsWith("(retired)"))
            assertFalse(TtsEngine.diagnosticLabel(id).startsWith("Kokoro"))
        }
        assertEquals(setOf(TtsEngine.KOKORO, TtsEngine.PIPER_MIRO, TtsEngine.POCKET_PAUL), TtsEngine.entries.toSet())
        assertEquals("future_voice", TtsEngine.diagnosticLabel("future_voice"))
    }

    @Test fun paulSelectionSurvivesRestartAndIsNotPiper() {
        val prefs = preferences()
        TtsComparisonStore(prefs).select(TtsEngine.POCKET_PAUL)
        assertEquals(TtsEngine.POCKET_PAUL, TtsComparisonStore(prefs).selectedEngine())
        assertFalse(TtsEngine.POCKET_PAUL.isPiper)
        assertTrue(TtsEngine.POCKET_PAUL.version.contains("Paul-p259"))
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
