package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable test-only configuration. No production preference is ever overwritten. */
class VoiceTestSessionStore(private val preferences: SharedPreferences) {
    @Synchronized fun recoverAfterProcessRestart() {
        active()?.let { finish(it.getString("id"), "interrupted", "Process restarted; temporary profile discarded.") }
    }
    @Synchronized fun active(): JSONObject? = preferences.getString("active", null)?.let(::JSONObject)
    @Synchronized fun begin(pack: String, savedSettings: JSONObject, metadata: JSONObject): String {
        check(active() == null) { "A test session is already active." }
        require(pack == VoiceTestPacks.SETUP)
        val id = UUID.randomUUID().toString()
        val record = JSONObject().put("id", id).put("pack", pack).put("case", "profile-lifecycle-v1")
            .put("repetition", 1).put("startedAtMs", System.currentTimeMillis()).put("state", "preparing")
            .put("savedSettings", savedSettings).put("metadata", metadata)
            .put("temporaryProfile", VoiceTestPacks.reference.id).put("temporaryVoice", TtsEngine.POCKET_PAUL.id)
            .put("temporaryAsr", "moonshine_small").put("profileVersion", "B673-reference-v1")
            .put("settingsScope", "test_session_only; production preferences unchanged")
            .put("audioRun", false).put("physicalTiming", "not_measured")
        check(preferences.edit().putString("active", record.toString()).commit()) { "Cannot save test session." }
        return id
    }
    @Synchronized fun ready(id: String, provenance: Map<String, String>) {
        val item = requireActive(id)
        item.put("state", "ready").put("provenance", JSONObject(provenance))
        check(preferences.edit().putString("active", item.toString()).commit()) { "Cannot save test readiness." }
    }
    @Synchronized fun finish(id: String, state: String, reason: String): Boolean {
        require(state in setOf("completed", "cancelled", "interrupted", "failed"))
        val item = active() ?: return false
        if (item.getString("id") != id) return false // A late callback cannot finish a newer run.
        check(state != "completed" || item.getString("state") == "ready")
        item.put("state", state).put("reason", reason).put("finishedAtMs", System.currentTimeMillis())
            .put("temporaryProfileDiscarded", true)
        val records = JSONArray(preferences.getString("history", "[]"))
        val retained = JSONArray()
        for (i in maxOf(0, records.length() - 19) until records.length()) retained.put(records.getJSONObject(i))
        retained.put(item)
        check(preferences.edit().putString("history", retained.toString()).remove("active").commit()) {
            "Cannot save test completion. Recovery record retained."
        }
        return true
    }
    @Synchronized fun report(): String = runCatching {
        val item = active() ?: JSONArray(preferences.getString("history", "[]")).let {
            if (it.length() == 0) null else it.getJSONObject(it.length() - 1)
        }
        item?.toString(2) ?: "No structured test sessions yet."
    }.getOrElse { "Test report unavailable: ${it.javaClass.simpleName}. Saved call settings are unchanged." }
    private fun requireActive(id: String): JSONObject = checkNotNull(active()).also {
        check(it.getString("id") == id) { "Stale test session." }
    }
}

object VoiceTestPacks {
    const val SETUP = "P1-setup-v1"
    val reference = TtsBenchmarkProfile(4, null, 0.9f, nativeStreaming = true,
        resetDecoder = true, leadingPeriod = true, bufferMs = 200)
    val planned = listOf("P2-load-v1", "P3-quality-v1", "P4-interruption-v1", "P5-acoustic-v1", "P6-handoff-v1", "P7-acceptance-v1")
    val scripts = linkedMapOf("H-v1" to "Hey Jarvis", "S-v1" to "Stop",
        "N-v1" to "Actually, tell me what two plus two is.", "G-v1" to "Goodbye Jarvis")
}
