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
    private fun compact(text: String, previous: String? = null): String = com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext().apply {
        restoreSummary(previous)
    }.compactSnapshot(listOf("You" to text))
    @Test fun chainedSummaryWritesRewritesAndExpiredTombstonesKeepOriginalSourceClock() {
        val prefs = PrivacyPreferences()
        var now = 1_000L
        var sourceTime = now
        var sourceText = "We discussed amber notebooks"
        val summary = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, { value, prior ->
            SourceTextPersistencePolicy.SummaryProof.fromHistory(value,
                listOf(SourceTextPersistencePolicy.SummarySource("m", "You", sourceText, sourceTime)), prior, now)
        }, { now })
        val first = compact(sourceText)
        summary.edit().putBoolean("sending", false).putString("short_term_summary", first).apply()
        assertEquals(first, summary.getString("short_term_summary", null))
        summary.edit().putString("short_term_summary", first).apply()
        assertEquals(first, summary.getString("short_term_summary", null))
        now += 1_000
        sourceTime = now
        sourceText = "A newer notebook topic"
        val rewritten = compact(sourceText, first)
        summary.edit().putString("short_term_summary", rewritten).apply()
        assertEquals(1_000L, prefs.values["short_term_summary_captured_at"])
        now = 1_000L + SourceTextPersistencePolicy.RETENTION_MS - 1
        assertEquals(rewritten, summary.getString("short_term_summary", null))
        now++
        assertNull(summary.getString("short_term_summary", null))
        assertEquals(1_000L, prefs.values["short_term_summary_captured_at"])
        sourceTime = now
        summary.edit().putString("short_term_summary", rewritten).apply()
        summary.edit().putString("short_term_summary", "A reworded stale amber summary").apply()
        assertNull(summary.getString("short_term_summary", null))
        assertFalse(prefs.durableText().contains("amber"))
        // A different exact compaction from independently timestamped fresh sources remains useful.
        sourceText = "Fresh orchids topic"
        val fresh = compact(sourceText)
        summary.edit().putString("short_term_summary", fresh).apply()
        assertEquals(fresh, summary.getString("short_term_summary", null))
        assertEquals(now, prefs.values["short_term_summary_captured_at"])
    }
    @Test fun unknownLegacyAndDifferentBundleCapsulesCannotBorrowAnEligibleBinding() {
        val prefs = PrivacyPreferences()
        var now = 2_000L
        val sources = listOf(SourceTextPersistencePolicy.SummarySource("m", "You", "Fresh orchids topic", 1_000L))
        val summary = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, { value, prior ->
            SourceTextPersistencePolicy.SummaryProof.fromHistory(value, sources, prior, now)
        }, { now })
        prefs.preferences.edit().putString("short_term_summary", "Legacy context").apply()
        assertNull(summary.getString("short_term_summary", null))
        val fresh = compact("Fresh orchids topic")
        summary.edit().putString("short_term_summary", fresh).apply()
        assertEquals(fresh, summary.getString("short_term_summary", null))
        // Simulate a different savedInstanceState capsule arriving with an otherwise eligible clock.
        summary.edit().putString("short_term_summary", "Different stale Bundle capsule").apply()
        assertNull(summary.getString("short_term_summary", null))
        assertEquals(1_000L, prefs.values["short_term_summary_captured_at"])
        summary.edit().putString("short_term_summary", "x".repeat(2_100) + " password: tiny").commit()
        assertFalse(prefs.durableText().contains("tiny"))
        assertFalse(prefs.durableText().contains("Different stale"))
    }
    @Test fun removalClearAndProcessRestoreRetainContentFreeLineage() {
        val prefs = PrivacyPreferences()
        var now = 1_000L
        var sourceTime = now
        var sourceText = "Amber notebooks"
        val prover = { value: String, prior: SourceTextPersistencePolicy.BoundSummary? ->
            SourceTextPersistencePolicy.SummaryProof.fromHistory(value,
                listOf(SourceTextPersistencePolicy.SummarySource("m", "You", sourceText, sourceTime)), prior, now)
        }
        val summary = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, prover, { now })
        val old = compact(sourceText)
        summary.edit().putString("short_term_summary", old).putBoolean("sending", true).apply()
        summary.edit().remove("short_term_summary").apply()
        assertEquals(1_000L, prefs.values["short_term_summary_captured_at"])
        summary.edit().clear().commit()
        assertFalse(prefs.values.containsKey("sending"))
        assertTrue(prefs.values.containsKey("short_term_summary_lineages"))
        now += SourceTextPersistencePolicy.RETENTION_MS
        sourceTime = now
        val restored = SourceTextPersistencePolicy.SummaryPreferences(prefs.preferences, prover, { now })
        restored.edit().putString("short_term_summary", old).apply()
        assertNull(restored.getString("short_term_summary", null))
        sourceText = "Fresh orchids"
        val fresh = compact(sourceText)
        restored.edit().putString("short_term_summary", fresh).apply()
        assertEquals(fresh, restored.getString("short_term_summary", null))
        // An eligible metadata clock alone cannot bind a different restored text.
        prefs.preferences.edit().putString("short_term_summary", "Different Bundle capsule").apply()
        assertNull(restored.getString("short_term_summary", null))
    }

}
