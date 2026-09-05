package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ChatEntry
import org.json.JSONObject

class ActionIntentRouter {
    fun classifyActionIntent(
        prompt: String,
        history: List<ChatEntry>
    ): com.battlesbudz.jarvis.v2.ai.ToolCall? {
        val normalized = prompt.trim()

        fun openApp(appName: String): com.battlesbudz.jarvis.v2.ai.ToolCall? {
            val cleanedName = appName.trim().trimEnd('.', '?', '!', ',')
            return cleanedName.takeIf {
                it.isNotBlank() && !it.equals("it", ignoreCase = true) &&
                    !it.equals("that", ignoreCase = true)
            }?.let {
                com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "open_app",
                    arguments = JSONObject().put("app", it).toString()
                )
            }
        }

        // Classify the request semantically enough for the small on-device
        // action set: an opening verb plus an app target means open_app,
        // regardless of where those words occur in the sentence.
        val openingVerb = Regex("""(?i)\b(open|launch|start)\b""").containsMatchIn(normalized)
        if (openingVerb) {
            val appMatch = Regex(
                """(?i)\b(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s+(?:for me|please|right now)|\s*[?.!,]|$)"""
            ).find(normalized)
            if (appMatch != null) return openApp(appMatch.groupValues[1])
        }

        // Resolve a pronoun or generic confirmation only when a recent turn
        // established a specific app-opening request or offer.
        val genericConfirmation = Regex(
            """(?i)^(?:(?:okay|ok|yes|sure)(?:\s+(?:thanks|thank you|can you|could you|please|do it|now|and))*|do it|go ahead|open it|open that)(?:\s+please)?[?.!]*$"""
        ).matches(normalized)
        if (genericConfirmation) {
            val priorApp = history.asReversed().asSequence()
                .mapNotNull { entry ->
                    val text = entry.text.trim()
                    val explicit = Regex(
                        """(?i)\b(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s+(?:for me|please|right now)|\s*[?.!,]|$)"""
                    ).find(text)?.groupValues?.getOrNull(1)
                    val offered = Regex(
                        """(?i)\b(?:can|could|will|shall)\s+(?:open|launch|start)\s+(?:the\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_-]*?)(?=\s*[?.!,]|$)"""
                    ).find(text)?.groupValues?.getOrNull(1)
                    explicit ?: offered
                }
                .firstOrNull { !it.equals("it", ignoreCase = true) && !it.equals("that", ignoreCase = true) }
            if (priorApp != null) return openApp(priorApp)
        }

        val volumeCommand = Regex("""(?i)\b(set|make|turn|adjust|change|raise|lower|increase|decrease)\b.*\bvolume\b""")
            .containsMatchIn(normalized)
        if (volumeCommand) {
            val volumeValue = Regex("""(?i)\bvolume\b[^0-9]{0,20}([0-9]{1,5})(?:\s*%)?\b""")
                .find(normalized)?.groupValues?.getOrNull(1)
            if (volumeValue != null) {
                return com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "set_volume",
                    arguments = JSONObject().put("level", volumeValue).toString()
                )
            }
        }

        if (
            Regex("""(?i)\b(what(?:'s| is| does)|how much|check|read|show)\b.{0,30}\bbattery\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\b(my|phone|device)'?s?\s+battery\b""").containsMatchIn(normalized) ||
            Regex("""(?i)\bbattery\b.{0,30}\b(say|percent|percentage|level|left|status|remaining)\b""").containsMatchIn(normalized)
        ) {
            return com.battlesbudz.jarvis.v2.ai.ToolCall(
                name = "read_battery",
                arguments = JSONObject().toString()
            )
        }
        return null
    }


    fun toolMatchesUserIntent(
        prompt: String,
        history: List<ChatEntry>,
        call: com.battlesbudz.jarvis.v2.ai.ToolCall
    ): Boolean {
        val normalized = prompt.trim()
        return when (call.name.lowercase()) {
            "read_battery" -> classifyActionIntent(normalized, history)?.name == "read_battery"
            "set_volume" -> classifyActionIntent(normalized, history)?.name == "set_volume"
            "open_app" -> classifyActionIntent(normalized, history)?.name == "open_app"
            else -> false
        }
    }


}
