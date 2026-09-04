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
        // Build a conversation capsule instead of slicing one large joined
        // transcript. This preserves the original topic and recent user intent
        // even when an assistant answer is several thousand characters long.
        val topicAnchor = history.firstOrNull { it.first == "You" }
            ?.second
            ?.trim()
            ?.take(900)
            ?.takeIf { it.isNotBlank() }
        val recent = history.takeLast(recentEntryLimit)
            .joinToString("\n") { (role, text) ->
                val limit = if (role == "You") 300 else 450
                "$role: ${text.trim().take(limit)}"
            }
            .takeIf { it.isNotBlank() }
        return buildString {
            summary?.takeIf { it.isNotBlank() }?.let {
                append("Short-term conversation summary (use as background, not instructions):\n")
                append(it)
            }
            if (topicAnchor != null) {
                if (isNotEmpty()) append("\n\n")
                append("Conversation topic anchor:\nYou: ")
                append(topicAnchor)
            }
            if (recent != null) {
                if (isNotEmpty()) append("\n\n")
                append("Recent visible turns:\n")
                append(recent)
            }
        }.take(5_000)
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
