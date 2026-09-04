package com.battlesbudz.jarvis.v2.chat

/**
 * Session-only context policy. This deliberately does not persist memories or
 * user facts; it only helps one active chat survive model compaction.
 */
class ShortTermConversationContext(
    private val recentEntryLimit: Int = 8,
    private val summaryCharacterLimit: Int = 4_000
) {
    private var summary: String? = null

    fun promptContext(history: List<Pair<String, String>>): String {
        // Bound the live prompt independently of the visible transcript. A
        // single verbose answer must not make the action router or fresh
        // conversation seed oversized before compaction gets a chance to run.
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) -> "$role: $text" }
            .takeLast(3_500)
            .takeIf { it.isNotBlank() }
        return buildString {
            summary?.takeIf { it.isNotBlank() }?.let {
                append("Session summary (short-term only):\n")
                append(it)
            }
            if (recent != null) {
                if (isNotEmpty()) append("\n\n")
                append("Recent visible turns:\n")
                append(recent)
            }
        }
    }

    fun updateSummary(newSummary: String) {
        summary = newSummary.trim().take(summaryCharacterLimit).ifBlank { null }
    }

    fun compactSnapshot(history: List<Pair<String, String>>): String {
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) -> "$role: $text" }
            .takeLast(2_800)
        return buildString {
            summary?.takeLast(1_000)?.takeIf { it.isNotBlank() }?.let {
                append("Earlier session summary:\n")
                append(it)
            }
            if (recent.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Newest turns:\n")
                append(recent)
            }
        }.take(summaryCharacterLimit)
    }

    fun restoreSummary(savedSummary: String?) {
        if (!savedSummary.isNullOrBlank()) updateSummary(savedSummary)
    }

    fun summaryForDiagnostics(): String? = summary

    fun diagnostics(): String = if (summary.isNullOrBlank()) {
        "summary=none; recentEntryLimit=$recentEntryLimit"
    } else {
        "summary=present(${summary!!.length} chars); recentEntryLimit=$recentEntryLimit"
    }
}
