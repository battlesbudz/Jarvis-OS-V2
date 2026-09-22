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

        val clauses = ActionRequestText.clauses(normalized)
        // Only a directed imperative/request can authorize a phone action.
        // "It'll open apps" and "how do I open apps?" remain ordinary conversation.
        clauses.firstNotNullOfOrNull(ActionRequestText::appTarget)?.let { return openApp(it) }

        // Resolve a pronoun or generic confirmation only when a recent turn
        // established a specific app-opening request or offer.
        val genericConfirmation = Regex(
            """(?i)^(?:(?:okay|ok|yes|sure)(?:\s+(?:thanks|thank you|can you|could you|please|do it|now|and))*|do it|go ahead|open it|open that)(?:\s+please)?[?.!]*$"""
        ).matches(normalized)
        if (genericConfirmation) {
            // Confirmation belongs to the immediately preceding request/offer;
            // an old mention of an app is not a standing authorization.
            val last = history.lastOrNull()
            val priorApp = last?.let { entry ->
                if (entry.role == "Jarvis") ActionRequestText.offeredApp(entry.text)
                else ActionRequestText.clauses(entry.text).firstNotNullOfOrNull(ActionRequestText::appTarget)
            }
            if (priorApp != null) return openApp(priorApp)
        }

        for (clause in clauses) {
            val volumeCommand = Regex("""(?i)^(?:set|make|turn|adjust|change|raise|lower|increase|decrease)\b.*\bvolume\b""")
                .containsMatchIn(clause)
            if (volumeCommand) {
                val value = com.battlesbudz.jarvis.v2.voice.SpokenVolumeLevel.fromTranscript(clause)
                if (value != null) return com.battlesbudz.jarvis.v2.ai.ToolCall(
                    name = "set_volume", arguments = JSONObject().put("level", value).toString())
            }
            if (Regex("""(?i)^(?:(?:what(?:'s| is)|how much|check|read|show|tell me)\b.{0,30}\bbattery\b.*|(?:my |phone |device )?battery(?: (?:level|status|percentage|percent|remaining))?)$""")
                    .matches(clause)) {
                return com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", JSONObject().toString())
            }
        }
        return null
    }


    fun toolMatchesUserIntent(
        prompt: String,
        history: List<ChatEntry>,
        call: com.battlesbudz.jarvis.v2.ai.ToolCall
    ): Boolean {
        val expected = classifyActionIntent(prompt, history)?.let(NativeActionDecoder::decode) ?: return false
        val proposed = NativeActionDecoder.decode(call) ?: return false
        if (expected.name != proposed.name) return false
        return when (expected.name) {
            "read_battery" -> true
            "set_volume" -> expected.arguments["level"]?.toIntOrNull()?.let { requested ->
                proposed.arguments["level"]?.toIntOrNull() == requested
            } == true
            "open_app" -> expected.arguments["app"]?.let { requested ->
                proposed.arguments["app"]?.trim()?.equals(requested.trim(), ignoreCase = true)
            } == true
            else -> false
        }
    }


}
