package com.battlesbudz.jarvis.v2.voice

/** Checks final dictated arguments, in addition to the existing action-intent and native validators. */
object FinalVoiceToolGuard {
    fun allows(transcript: String, name: String, arguments: Map<String, String>): Boolean {
        val text = transcript.lowercase().replace('’', '\'')
        if (Regex("\\b(?:never mind|nevermind|cancel|do not|don't|dont|stop listening)\\b").containsMatchIn(text)) return false
        return when (name) {
            "set_volume" -> {
                val finalNumber = SpokenVolumeLevel.fromTranscript(text)
                finalNumber != null && finalNumber == arguments["level"]?.toIntOrNull()
            }
            "open_app" -> {
                val packageName = arguments["package"].orEmpty().lowercase()
                val target = arguments["app"].orEmpty().lowercase().trim()
                // Do not let an unspoken package override the named app.
                if (packageName.isNotBlank() && !text.contains(packageName)) return false
                if (target.isBlank()) return packageName.isNotBlank() && text.contains(packageName)
                val correction = Regex("\\b(?:actually|instead|make that|no)\\b").findAll(text).lastOrNull()
                val correctionOffset = correction?.takeIf { text.substring(it.range.last + 1).isNotBlank() }?.range?.last?.plus(1) ?: 0
                val verbOffset = Regex("\\b(?:open|launch|start)\\b").findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0
                val relevant = text.substring(maxOf(correctionOffset, verbOffset))
                Regex("(?<![a-z0-9])" + target.filterNot { it.isWhitespace() }.map { Regex.escape(it.toString()) }.joinToString("\\s*") + "(?![a-z0-9])").containsMatchIn(relevant)
            }
            "read_battery" -> true
            else -> false
        }
    }
}
