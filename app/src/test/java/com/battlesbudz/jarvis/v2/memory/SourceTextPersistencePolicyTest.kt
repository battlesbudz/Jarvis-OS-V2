package com.battlesbudz.jarvis.v2.memory

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** In-memory Android preference boundary shared by the new privacy tests. */
class PrivacyPreferences {
    val values = linkedMapOf<String, Any?>()
    val preferences: SharedPreferences
    init {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putLong", "putInt", "putBoolean", "putFloat", "putStringSet" -> { values[args!![0] as String] = args[1]; editor }
                "remove" -> { values.remove(args!![0]); editor }
                "clear" -> { values.clear(); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString", "getLong", "getInt", "getBoolean", "getFloat", "getStringSet" -> values[args!![0]] ?: args[1]
                "getAll" -> values.toMutableMap()
                "contains" -> values.containsKey(args!![0])
                else -> null
            }
        } as SharedPreferences
    }
    fun durableText(): String = values.values.joinToString("\n")
}

class SourceTextPersistencePolicyTest {
    @Test fun scansCompleteBoundedSourcesAndRejectsOverflow() {
        listOf("password: tiny", "Card number 4111 1111 1111 1111", "access code: 1234").forEach { secret ->
            assertTrue(SourceTextPersistencePolicy.excluded("harmless ".repeat(400) + secret))
        }
        assertFalse(SourceTextPersistencePolicy.excluded("I like apricots"))
        assertTrue(SourceTextPersistencePolicy.excluded("x".repeat(SourceTextPersistencePolicy.MAX_TEXT_CHARS + 1)))
    }
    @Test fun exactExpiryUnknownAndOverflowTimesFailClosed() {
        val captured = 1_000L
        val end = captured + SourceTextPersistencePolicy.RETENTION_MS
        assertTrue(SourceTextPersistencePolicy.eligible(captured, end - 1))
        assertFalse(SourceTextPersistencePolicy.eligible(captured, end))
        assertFalse(SourceTextPersistencePolicy.eligible(0, captured))
        assertFalse(SourceTextPersistencePolicy.eligible(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(SourceTextPersistencePolicy.eligible(captured + 1, captured))
    }
    @Test fun chainedSummaryWritesAndRewritesKeepOldestSourceClock() {
        val prefs = PrivacyPreferences()
        var now = 1_000L
        var source = now
        val summary = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, { source }, { now })
        summary.edit().putBoolean("sending", false).putString("short_term_summary", "We discussed amber notebooks").apply()
        now += 1_000
        source = now
        summary.edit().putString("short_term_summary", "A rewritten amber notebook summary").apply()
        assertEquals(1_000L, prefs.values["short_term_summary_captured_at"])
        now = 1_000L + SourceTextPersistencePolicy.RETENTION_MS - 1
        assertNotNull(summary.getString("short_term_summary", null))
        now++
        assertNull(summary.getString("short_term_summary", null))
        source = 1_000L
        summary.edit().putString("short_term_summary", "Late amber summary").apply()
        assertNull(summary.getString("short_term_summary", null))
        assertFalse(prefs.durableText().contains("amber"))
    }
    @Test fun summarySecretsAndLegacyProvenanceAreNeverRenewed() {
        val prefs = PrivacyPreferences()
        prefs.preferences.edit().putString("short_term_summary", "Legacy context").apply()
        val summary = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, { 1_000L }, { 2_000L })
        assertNull(summary.getString("short_term_summary", null))
        summary.edit().putString("short_term_summary", "x".repeat(2_100) + " password: tiny").commit()
        assertFalse(prefs.durableText().contains("tiny"))
    }
}
