package com.battlesbudz.jarvis.v2.memory

import android.content.SharedPreferences

/** A source copy never gets a new retention clock when it is rewritten or summarized. */
object SourceTextPersistencePolicy {
    const val RETENTION_MS = MemoryArchivePolicy.RETENTION_MS
    const val MAX_TEXT_CHARS = MemoryArchivePolicy.MAX_TEXT_CHARS
    const val EXCLUDED = "[Source text excluded for privacy.]"
    const val EXPIRED = "[Source text expired or capture time unavailable.]"
    fun eligible(capturedAtMs: Long, nowMs: Long): Boolean = capturedAtMs > 0 && nowMs > 0 &&
        capturedAtMs <= nowMs && capturedAtMs <= Long.MAX_VALUE - RETENTION_MS &&
        nowMs < capturedAtMs + RETENTION_MS
    /** Never truncate before detection; oversized input fails closed. */
    fun excluded(text: String): Boolean = text.length > MAX_TEXT_CHARS || MemoryArchivePolicy.containsSecret(text)
    fun placeholder(texts: List<String>, capturedAtMs: Long, nowMs: Long): String? = when {
        texts.any { it.contains(EXCLUDED) || excluded(it) } -> EXCLUDED
        texts.any { it.contains(EXPIRED) } || !eligible(capturedAtMs, nowMs) -> EXPIRED
        else -> null
    }
    fun originalTimestamp(incoming: Long, previous: Long?): Long =
        if (previous == null) incoming else minOf(incoming, previous)

    /** All summary callers share this boundary, including Activity save and compaction writes. */
    class SummaryPreferences(
        private val delegate: SharedPreferences,
        private val sourceTimestamp: () -> Long,
        private val clock: () -> Long = System::currentTimeMillis,
        private val summaryKey: String = "short_term_summary",
        private val onRejected: () -> Unit = {}
    ) : SharedPreferences by delegate {
        private val timestampKey = "${summaryKey}_captured_at"
        override fun getString(key: String?, defValue: String?): String? {
            if (key != summaryKey) return delegate.getString(key, defValue)
            val text = delegate.getString(key, null) ?: return defValue
            val captured = delegate.getLong(timestampKey, 0)
            if (placeholder(listOf(text), captured, clock()) == null) return text
            delegate.edit().remove(summaryKey).remove(timestampKey).apply()
            onRejected()
            return defValue
        }
        override fun getAll(): MutableMap<String, *> {
            getString(summaryKey, null)
            return delegate.all
        }
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != summaryKey) { editor.putString(key, value); return this }
                    val previous = if (delegate.contains(summaryKey)) delegate.getLong(timestampKey, 0) else null
                    val captured = originalTimestamp(sourceTimestamp(), previous)
                    if (value != null && placeholder(listOf(value), captured, clock()) == null) {
                        editor.putString(key, value).putLong(timestampKey, captured)
                    } else {
                        editor.remove(summaryKey).remove(timestampKey)
                        onRejected()
                    }
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                override fun clear(): SharedPreferences.Editor { editor.clear(); onRejected(); return this }
                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    if (key == summaryKey) editor.remove(timestampKey)
                    return this
                }
            }
        }
    }
}
