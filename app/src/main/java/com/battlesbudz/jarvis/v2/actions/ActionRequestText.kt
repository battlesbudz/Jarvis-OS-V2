package com.battlesbudz.jarvis.v2.actions

/** Recognize directed requests, not verbs embedded in descriptions or quoted examples. */
internal object ActionRequestText {
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”")
    private val lead = Regex("""(?i)^(?:(?:hey\s+)?jarvis\s*[,,:]?\s+)?(?:(?:um|uh|okay|ok|well|so|alright|please)\s*[,,:]?\s+)*(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?|i\s+(?:want|need)\s+you\s+to\s+)?(?:please\s+)?""")
    private val trailing = Regex("""(?i)(?:\s+(?:for me|please|right now|now|sir))+$""")

    fun clauses(text: String): List<String> = quoted.replace(text, " ")
        .split(Regex("[.!?;\\n]+"))
        .map { lead.replaceFirst(it.trim(), "").trim() }.filter { it.isNotEmpty() }

    /** Split only an explicit sequence of final, directed action clauses. */
    fun actionClauses(text: String): List<String> {
        val unquoted = quoted.replace(text, " ").trim()
        if (unquoted.isBlank() || Regex("""(?i)\b(?:don't|do not|never|how do i)\b""").containsMatchIn(unquoted)) return emptyList()
        val actionLead = """(?:can|could|would|will)\s+you\b|(?:open|launch|start|set|make|turn|adjust|change|raise|lower|increase|decrease|read|check|show|tell)\b"""
        val discourse = unquoted.replaceFirst(Regex("""(?i)^(?:(?:but\s+)?actually|and\s+then|then)\s+(?=$actionLead)"""), "")
        val retried = discourse.replaceFirst(Regex("""(?i)^(?:i\s+(?:said|asked)(?:\s+you)?[, ]+)(?=(?:can|could|would|will)\s+you\b|please\s+(?:open|launch|start|set|read|check|show|tell)\b)"""), "")
        return retried.split(Regex("""(?i)[.!?;\n]+|\s*(?:,?\s+and\s+then\s+|,?\s+then\s+|,?\s+and\s+)"""))
            .map {
                val normalized = trailing.replace(lead.replaceFirst(it.trim(), "").trim(), "").trim().trimEnd('.', '!', '?')
                Regex("""(?i)\s+after that$""").replace(normalized, "").trim()
            }
            .filter { it.isNotBlank() }
    }

    /** Bounded natural-language forms for the current phone battery reading. */
    fun batteryRequest(clause: String): Boolean {
        val text = clause.lowercase().trim()
        val label = "(?:battery(?: (?:level|status|percentage|percent|remaining))?)"
        return listOf(
            "(?:what(?:'s| is)|check|read|show) (?:my |the |phone |device )?$label(?: is)?",
            "tell me (?:what )?(?:my |the |phone |device )?$label(?: is)?",
            "tell me how much battery (?:i have(?: left)?|is left|does my phone have)",
            "how much (?:my )?battery(?: (?:do i have|is left|does my phone have))?",
            "(?:my |phone |device )?$label"
        ).any { Regex("^(?:$it)$").matches(text) }
    }

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
