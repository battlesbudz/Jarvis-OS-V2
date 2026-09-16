package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceTestSessionStoreTest {
    private class Memory {
        val data = mutableMapOf<String, String?>()
        var fail = false
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> data[args[0]] ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        fun editor(): SharedPreferences.Editor {
            val puts = mutableMapOf<String, String?>()
            val removes = mutableSetOf<String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { puts[args[0] as String] = args[1] as String?; editor }
                    "remove" -> { removes.add(args[0] as String); editor }
                    "commit" -> { if (!fail) { removes.forEach(data::remove); data.putAll(puts) }; !fail }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
    private fun begin(store: VoiceTestSessionStore) = store.begin(VoiceTestPacks.SETUP,
        JSONObject().put("engine", "kokoro").put("call_profile_kokoro", "legacy-profile"), JSONObject().put("sourceCommit", "test"))

    @Test fun completionDiscardsOnlyTemporaryProfileAndRetainsExactSnapshot() {
        val memory = Memory(); val store = VoiceTestSessionStore(memory.prefs)
        val id = begin(store)
        assertEquals(VoiceTestPacks.reference.id, store.active()!!.getString("temporaryProfile"))
        store.ready(id, mapOf("sha256.paul.wav" to "hash"))
        assertTrue(store.finish(id, "completed", "done"))
        assertNull(store.active())
        val report = JSONObject(store.report())
        assertEquals("legacy-profile", report.getJSONObject("savedSettings").getString("call_profile_kokoro"))
        assertFalse(report.getBoolean("audioRun"))
        assertTrue(report.getBoolean("temporaryProfileDiscarded"))
        assertEquals(setOf("history"), memory.data.keys)
    }
    @Test fun processRestartRecoversWithoutApplyingTestProfile() {
        val memory = Memory(); begin(VoiceTestSessionStore(memory.prefs))
        val restored = VoiceTestSessionStore(memory.prefs)
        restored.recoverAfterProcessRestart()
        assertNull(restored.active())
        assertEquals("interrupted", JSONObject(restored.report()).getString("state"))
        restored.recoverAfterProcessRestart()
        assertEquals("interrupted", JSONObject(restored.report()).getString("state"))
    }
    @Test fun staleCompletionCannotCloseANewerSession() {
        val store = VoiceTestSessionStore(Memory().prefs)
        val first = begin(store); store.finish(first, "cancelled", "cancel")
        val second = begin(store)
        assertFalse(store.finish(first, "failed", "late callback"))
        assertEquals(second, store.active()!!.getString("id"))
    }
    @Test fun cannotStartAnotherPackOrClaimCompletionBeforeReady() {
        val store = VoiceTestSessionStore(Memory().prefs); val id = begin(store)
        assertThrows(IllegalStateException::class.java) { begin(store) }
        assertThrows(IllegalStateException::class.java) { store.finish(id, "completed", "not verified") }
        assertEquals(id, store.active()!!.getString("id"))
    }
    @Test fun failedPersistenceRetainsRecoverableJournal() {
        val memory = Memory(); val store = VoiceTestSessionStore(memory.prefs); val id = begin(store)
        memory.fail = true
        assertThrows(IllegalStateException::class.java) { store.finish(id, "cancelled", "cancel") }
        assertNotNull(store.active())
        memory.fail = false
        VoiceTestSessionStore(memory.prefs).recoverAfterProcessRestart()
        assertNull(store.active())
    }
    @Test fun missingModelFailureAndCancellationStayDistinctAcrossReload() {
        val memory = Memory(); val store = VoiceTestSessionStore(memory.prefs)
        store.finish(begin(store), "failed", "missing model")
        assertEquals("failed", JSONObject(VoiceTestSessionStore(memory.prefs).report()).getString("state"))
        store.finish(begin(store), "cancelled", "user cancelled")
        assertEquals("cancelled", JSONObject(store.report()).getString("state"))
    }
    @Test fun loadCheckpointsSurviveRestartAndRejectStaleWriters() {
        val memory = Memory(); val store = VoiceTestSessionStore(memory.prefs)
        val id = store.begin(VoiceTestPacks.LOAD, JSONObject(), JSONObject())
        store.ready(id, emptyMap())
        store.checkpoint(id, JSONObject().put("conditionA", "completed").put("conditionB", "running"))
        assertThrows(IllegalStateException::class.java) { store.checkpoint("stale", JSONObject()) }
        VoiceTestSessionStore(memory.prefs).recoverAfterProcessRestart()
        val report = JSONObject(store.report())
        assertTrue(report.getBoolean("audioRun"))
        assertEquals("completed", report.getJSONObject("loadEvidence").getString("conditionA"))
        assertEquals("interrupted", report.getString("state"))
    }
    @Test fun registryNeverAdvertisesFutureAudioPacksAsRunnable() {
        val store = VoiceTestSessionStore(Memory().prefs)
        for (pack in VoiceTestPacks.planned) assertThrows(IllegalArgumentException::class.java) {
            store.begin(pack, JSONObject(), JSONObject())
        }
        assertEquals("threads-4-native-stream-speed-0.9-reset-true-period-true-buffer-200", VoiceTestPacks.reference.id)
    }
}
