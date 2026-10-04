package com.battlesbudz.jarvis.v2.actions

/** Recognize directed requests, not verbs embedded in descriptions or quoted examples. */
internal object ActionRequestText {
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”")
    private val lead = Regex("""(?i)^(?:(?:hey\s+)?jarvis\s*[,,:]?\s+)?(?:(?:um|uh|okay|ok|well|so|alright|please)\s*[,,:]?\s+)*(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?|i\s+(?:want|need)\s+you\s+to\s+)?(?:please\s+)?""")
    private val trailing = Regex("""(?i)(?:\s+(?:for me|please|right now|now|sir))+$""")

    fun normalizedRequest(text: String): String = lead.replaceFirst(quoted.replace(text, " ").trim(), "").trim()

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
        val directed = lead.replaceFirst(retried, "").trim()
        return directed.split(Regex("""(?i)[.!?;\n]+|,\s*(?=$actionLead|please\s+)|\s*(?:,?\s+and\s+then\s+|,?\s+then\s+|,?\s+and\s+)"""))
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

    /** Bounded natural-language forms for media playback control. Returns the strict verb. */
    fun mediaAction(clause: String): String? {
        val text = clause.lowercase().trim()
        // A media noun is required: bare verbs like "stop" or "next" are too
        // ambiguous to become phone actions on their own.
        val noun = """(?:music|media|songs?|tracks?|playback|tunes?)"""
        return when {
            Regex("""^(?:pause|stop)\b.*\b$noun\b""").containsMatchIn(text) -> "pause"
            Regex("""^(?:play|resume)\b.*\b$noun\b""").containsMatchIn(text) -> "play"
            Regex("""^toggle\b.*\b$noun\b""").containsMatchIn(text) -> "toggle"
            Regex("""^(?:next|skip)\b.*\b$noun\b""").containsMatchIn(text) -> "next"
            Regex("""^(?:previous|last|go\s+back)\b.*\b$noun\b""").containsMatchIn(text) -> "previous"
            else -> null
        }
    }

    /** Bounded natural-language forms for opening a website. Returns the raw URL-ish target. */
    fun websiteTarget(clause: String): String? {
        val text = clause.trim()
        // A dot (or explicit scheme) distinguishes a website from an app name,
        // so "open Chrome" still routes to open_app.
        return Regex("""(?i)^(?:open|launch|visit|go\s+to)\s+(https?://\S+|\S*\.\S+.*)$""")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Bounded natural-language forms for Android settings screens. Returns the screen key. */
    fun settingsScreen(clause: String): String? {
        val text = clause.lowercase().trim()
        val name = Regex("""^(?:open|show|go\s+to)\s+(?:the\s+)?(wi-?fi|bluetooth|display|sound|apps?|battery|location|storage|network)\s+settings?$""")
            .matchEntire(text)?.groupValues?.get(1) ?: return null
        return when (name.replace("-", "").removeSuffix("s")) {
            "wifi" -> "wifi"
            "bluetooth" -> "bluetooth"
            "display" -> "display"
            "sound" -> "sound"
            "app" -> "apps"
            "battery" -> "battery"
            "location" -> "location"
            "storage" -> "storage"
            "network" -> "network"
            else -> null
        }
    }

    /** Bounded natural-language forms for map directions. Returns the destination. */
    fun navigationTarget(clause: String): String? {
        val text = clause.trim()
        return Regex("""(?i)^(?:navigate|drive)\s+to\s+(.+)$""")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""(?i)^(?:get|show(?: me)?)\s+directions\s+to\s+(.+)$""")
                .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""(?i)^take\s+me\s+to\s+(.+)$""")
                .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
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
