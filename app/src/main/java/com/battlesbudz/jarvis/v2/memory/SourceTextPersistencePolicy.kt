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

    data class SummarySource(val id: String, val role: String, val text: String, val capturedAtMs: Long)
    data class BoundSummary(val text: String, val capturedAtMs: Long, val sourceKey: String)
    class SummaryProof private constructor(val text: String, val capturedAtMs: Long, val sourceKey: String) {
        companion object {
            /** Proof is issued only for an exact compaction of complete timestamped source copies. */
            fun fromHistory(text: String, sources: List<SummarySource>, previous: BoundSummary?, nowMs: Long): SummaryProof? {
                if (sources.isEmpty() || sources.any { placeholder(listOf(it.role, it.text), it.capturedAtMs, nowMs) != null }) return null
                if (previous != null && placeholder(listOf(previous.text), previous.capturedAtMs, nowMs) != null) return null
                for (candidate in listOf(sources, sources.dropLast(1))) {
                    if (candidate.isEmpty()) continue
                    val context = com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext()
                    context.restoreSummary(previous?.text)
                    if (context.compactSnapshot(candidate.map { it.role to it.text }) != text) continue
                    val captured = minOf(candidate.minOf { it.capturedAtMs }, previous?.capturedAtMs ?: Long.MAX_VALUE)
                    val binding = candidate.joinToString("") { "${it.id.length}:${it.id}${it.capturedAtMs}:${it.role.length}:${it.role}${it.text.length}:${it.text}" } + previous?.sourceKey.orEmpty()
                    return SummaryProof(text, captured, MemoryPolicy.sourceKey(binding))
                }
                return null
            }
        }
    }

    /** Generic writes carry no authority. A private proof binds exact text to complete sources. */
    class SummaryPreferences(
        private val delegate: SharedPreferences,
        private val proveSummary: (String, BoundSummary?) -> SummaryProof?,
        private val clock: () -> Long = System::currentTimeMillis,
        private val summaryKey: String = "short_term_summary",
        private val onRejected: () -> Unit = {}
    ) : SharedPreferences by delegate {
        private val timestampKey = "${summaryKey}_captured_at"
        private val bindingKey = "${summaryKey}_binding"
        private val lineageKey = "${summaryKey}_lineages"
        private fun lineages() = runCatching { org.json.JSONObject(delegate.getString(lineageKey, "{}")) }.getOrDefault(org.json.JSONObject())
        private fun bound(): BoundSummary? {
            val text = delegate.getString(summaryKey, null) ?: return null
            val binding = runCatching { org.json.JSONObject(delegate.getString(bindingKey, "{}")) }.getOrDefault(org.json.JSONObject())
            val hash = MemoryPolicy.sourceKey(text)
            val lineage = lineages().optJSONObject(hash) ?: return null
            val captured = delegate.getLong(timestampKey, 0)
            if (binding.optString("textKey") != hash || binding.optString("sourceKey") != lineage.optString("sourceKey") ||
                lineage.optBoolean("blocked") || captured != lineage.optLong("capturedAtMs", 0) ||
                placeholder(listOf(text), captured, clock()) != null) return null
            return BoundSummary(text, captured, binding.optString("sourceKey"))
        }
        private fun tombstone(text: String, edit: SharedPreferences.Editor, ledger: org.json.JSONObject = lineages()) {
            val hash = MemoryPolicy.sourceKey(text)
            val old = ledger.optJSONObject(hash) ?: org.json.JSONObject().put("capturedAtMs", delegate.getLong(timestampKey, 0))
            old.put("blocked", true)
            if (ledger.has(hash) || ledger.length() < 20_000) ledger.put(hash, old)
            edit.putString(lineageKey, ledger.toString())
        }
        override fun getString(key: String?, defValue: String?): String? {
            if (key != summaryKey) return delegate.getString(key, defValue)
            val text = delegate.getString(summaryKey, null) ?: return defValue
            bound()?.let { return it.text }
            val edit = delegate.edit().remove(summaryKey)
            tombstone(text, edit)
            // Capture and binding metadata survive expiry/rejection; they never become a new clock.
            edit.apply()
            onRejected()
            return defValue
        }
        override fun getAll(): MutableMap<String, *> { getString(summaryKey, null); return delegate.all }
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != summaryKey) { editor.putString(key, value); return this }
                    val prior = bound()
                    if (value != null && prior?.text == value) {
                        // An unchanged eligible capsule retains its already verified exact binding.
                        editor.putString(summaryKey, value); return this
                    }
                    val proof = value?.let { proveSummary(it, prior) }
                    val ledger = lineages()
                    val hash = value?.let(MemoryPolicy::sourceKey)
                    val old = hash?.let { ledger.optJSONObject(it) }
                    val captured = proof?.let { originalTimestamp(it.capturedAtMs, old?.optLong("capturedAtMs", 0)) } ?: 0
                    if (value != null && proof?.text == value && old?.optBoolean("blocked") != true &&
                        (old != null || ledger.length() < 20_000) && placeholder(listOf(value), captured, clock()) == null) {
                        val source = proof!!.sourceKey
                        ledger.put(hash!!, org.json.JSONObject().put("capturedAtMs", captured).put("sourceKey", source).put("blocked", false))
                        editor.putString(summaryKey, value).putLong(timestampKey, captured)
                            .putString(bindingKey, org.json.JSONObject().put("textKey", hash).put("sourceKey", source).toString())
                            .putString(lineageKey, ledger.toString())
                    } else {
                        value?.let { tombstone(it, editor, ledger) }
                        delegate.getString(summaryKey, null)?.let { tombstone(it, editor, ledger) }
                        editor.remove(summaryKey)
                        onRejected()
                    }
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { editor.putFloat(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                override fun clear(): SharedPreferences.Editor {
                    // Clear session state while retaining only content-free retention provenance.
                    val ledger = lineages()
                    delegate.getString(summaryKey, null)?.let { tombstone(it, editor, ledger) }
                    val captured = delegate.getLong(timestampKey, 0)
                    val binding = delegate.getString(bindingKey, null)
                    editor.clear().putString(lineageKey, ledger.toString()).putLong(timestampKey, captured)
                    binding?.let { editor.putString(bindingKey, it) }
                    onRejected(); return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key == summaryKey) {
                        delegate.getString(summaryKey, null)?.let { tombstone(it, editor) }
                        onRejected()
                    }
                    editor.remove(key)
                    return this
                }
            }
        }
    }
}
