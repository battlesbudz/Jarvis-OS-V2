package com.battlesbudz.jarvis.v2.ai

/** A transport success is not evidence that a result answers the question. */
object ReferenceEvidencePolicy {
    private val stop = setOf("what", "who", "when", "where", "how", "why", "is", "are", "was", "were", "do", "did", "does", "you", "your", "the", "a", "an", "to", "of", "and", "in", "on", "for", "it", "that", "this", "me", "my", "i", "tell", "about", "make", "made", "use", "using", "please", "search", "wikipedia", "verify", "conversation", "domain")
    private fun tokens(text: String) = Regex("[a-z][a-z0-9'-]+").findAll(text.lowercase()).map { it.value.trimEnd('\'') }.filterNot { it in stop }.toSet()
    fun relevant(query: String, title: String, text: String): Boolean {
        if (text.isBlank()) return false
        val parts = query.split("\nConversation domain: ", limit = 2)
        val core = tokens(parts[0].replace(Regex("https?://\\S+"), ""))
        val found = tokens("$title $text")
        if (core.isEmpty()) return false
        val acronym = Regex("(?i)^(?:what is|what's|who is)?\\s*([a-z]{2,5})[?!.]*$").matchEntire(parts[0].trim())?.groupValues?.get(1)?.lowercase()
        if (acronym != null && acronym !in found) return false
        if (core.count { it in found } < minOf(core.size, maxOf(1, (core.size + 1) / 2))) return false
        if (parts.size == 2) {
            val domain = tokens(parts[1].replace(Regex("https?://\\S+"), ""))
            if (domain.isNotEmpty() && domain.count { it in found } < minOf(2, domain.size)) return false
        }
        // Do not substitute holiday trivia for the shop's actual promotion.
        val business = Regex("(?i)\\b([a-z][a-z']+)\\s+says\\b").find(query)?.groupValues?.get(1)?.trimEnd('\'', 's')
        if (business != null && found.none { it.trimEnd('\'', 's') == business }) return false
        return true
    }
    fun passage(query: String, title: String, article: String, maxChars: Int = 1800): String {
        val terms = tokens(query)
        val paragraphs = article.split(Regex("\\n\\s*\\n|\\n")).flatMap { it.chunked(minOf(maxChars, 1200)) }
        val ranked = paragraphs.withIndex().sortedByDescending { (_, text) -> tokens(text).count { it in terms } }
        val chosen = mutableListOf<IndexedValue<String>>()
        var remaining = maxChars
        for (part in ranked) {
            val text = part.value.trim()
            if (text.isBlank() || text.length > remaining) continue
            chosen += IndexedValue(part.index, text); remaining -= text.length + 1
        }
        val result = chosen.sortedBy { it.index }.joinToString("\n") { it.value }
        return result.takeIf { relevant(query, title, it) }.orEmpty()
    }
}
