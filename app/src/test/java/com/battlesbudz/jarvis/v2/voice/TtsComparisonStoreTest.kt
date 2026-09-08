package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TtsComparisonStoreTest {
    @Test fun selectionsAndResultsSurviveRestartWithoutMixingModels() {
        val prefs = preferences()
        val store = TtsComparisonStore(prefs)
        store.select(TtsEngine.PIPER)
        val metrics = TtsSessionMetrics(100, 200, 400, 2000, 20, 1f, 0, 1, 2, 50, "abc", 4, true, null)
        store.add(TtsEngine.PIPER_RYAN, "benchmark-v1", "short-v1", metrics)
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.PIPER, restored.selectedEngine())
        val entry = restored.records().single()
        assertEquals(TtsEngine.PIPER_RYAN.id, entry.getString("engine"))
        assertEquals(0.2, entry.getDouble("rtf"), 0.0001)
        assertTrue(entry.getBoolean("completed"))
        repeat(45) { restored.add(TtsEngine.PIPER, "voice-call", "$it", metrics.copy(completed = false, error = "stopped")) }
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
        assertEquals("future_voice", TtsEngine.diagnosticLabel("future_voice"))
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
