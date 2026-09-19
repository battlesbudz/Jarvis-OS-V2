package com.battlesbudz.jarvis.v2.actions

/** Recognize directed requests, not verbs embedded in descriptions or quoted examples. */
internal object ActionRequestText {
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”")
    private val lead = Regex("""(?i)^(?:(?:hey\s+)?jarvis\s*[,,:]?\s+)?(?:(?:um|uh|okay|ok|well|so|alright|please)\s*[,,:]?\s+)*(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?|i\s+(?:want|need)\s+you\s+to\s+)?(?:please\s+)?""")
    private val trailing = Regex("""(?i)(?:\s+(?:for me|please|right now|now|sir))+$""")

    fun clauses(text: String): List<String> = quoted.replace(text, " ")
        .split(Regex("[.!?;\\n]+"))
        .map { lead.replaceFirst(it.trim(), "").trim() }.filter { it.isNotEmpty() }

    fun appTarget(clause: String): String? {
        val target = Regex("""(?i)^(?:open|launch|start)\s+(?:up\s+)?(?:the\s+)?(.+)$""")
            .matchEntire(clause)?.groupValues?.get(1) ?: return null
        return cleanTarget(target)
    }

    fun offeredApp(text: String): String? {
        val unquoted = quoted.replace(text, " ").trim()
        val target = Regex("""(?i)^(?:(?:sir|yes|certainly)[,.]?\s+)?(?:shall i|would you like me to|i can|i could)\s+(?:open|launch|start)\s+(?:the\s+)?([^?.!]+)[?.!]*$""")
            .matchEntire(unquoted)?.groupValues?.get(1) ?: return null
        return cleanTarget(target)
    }

    private fun cleanTarget(text: String): String? {
        val target = trailing.replace(text.trim(), "").trim()
        val words = target.lowercase(java.util.Locale.ROOT).split(Regex("\\s+"))
        if (words.isEmpty() || words.size > 8 || words.any { it in setOf("would", "could", "should", "because", "instead", "and", "or") }) return null
        if (target.lowercase(java.util.Locale.ROOT) in setOf("it", "that", "apps", "applications", "an app", "any app", "an application", "everything", "anything", "source")) return null
        return target.takeIf { it.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} .&'_-]*")) }
    }
}
