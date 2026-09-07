package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AsrComparisonStoreTest {
    private val metrics = AsrCaptureMetrics(100, 400, 2000, 500, 150, 350, 4, 70, "trailing_silence")

    @Test fun newInstallDefaultsToMoonshineButExplicitZipformerSurvives() {
        val preferences = preferences()
        val store = AsrComparisonStore(preferences)
        assertEquals(AsrEngine.MOONSHINE, store.selectedEngine())
        store.select(AsrEngine.ZIPFORMER)
        assertEquals(AsrEngine.ZIPFORMER, AsrComparisonStore(preferences).selectedEngine())
    }

    @Test fun keepsEngineReferenceAndTimingAcrossStoreRecreation() {
        val preferences = preferences()
        val store = AsrComparisonStore(preferences)
        store.select(AsrEngine.MOONSHINE)
        store.add("one", AsrEngine.MOONSHINE, metrics, "hello ants")
        store.setReference("one", "hello astronauts")
        store.update("one", "final_to_first_text_ms", 800L)
        val restored = AsrComparisonStore(preferences)
        assertEquals(AsrEngine.MOONSHINE, restored.selectedEngine())
        assertTrue(restored.snapshot().contains("word_error_rate_percent=50.0"))
        assertTrue(restored.snapshot().contains("final_to_first_text_ms=800"))
    }

    @Test fun switchingDoesNotRelabelExistingMeasurementsAndRetentionIsBounded() {
        val store = AsrComparisonStore(preferences())
        repeat(23) { store.add(it.toString(), AsrEngine.ZIPFORMER, metrics, "hello") }
        store.select(AsrEngine.MOONSHINE)
        assertEquals(20, store.records().size)
        assertEquals("3", store.records().first().getString("id"))
        assertTrue(store.records().all { it.getString("engine") == AsrEngine.ZIPFORMER.id })
        store.update("0", "late_update", 1)
        assertEquals(20, store.records().size)
        assertTrue(store.snapshot().contains("unscored"))
    }

    @Test fun missingLivePartialStaysUnavailable() {
        val store = AsrComparisonStore(preferences())
        store.add("one", AsrEngine.ZIPFORMER, metrics.copy(firstPartialAfterSpeechMs = null), "hello")
        assertTrue(store.snapshot().contains("speech_detected_to_first_partial_ms=unavailable"))
        store.setReference("one", "hello")
        store.setReference("one", "")
        assertTrue(store.snapshot().contains("unscored"))
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
