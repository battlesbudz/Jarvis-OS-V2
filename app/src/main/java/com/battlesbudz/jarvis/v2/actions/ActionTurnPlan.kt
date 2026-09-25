package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ChatEntry

/** An all-or-nothing interpretation of the user's final action request. */
sealed interface ActionTurnPlan {
    data object NotAction : ActionTurnPlan
    data class Ready(val steps: List<Step>) : ActionTurnPlan { init { require(steps.size in 1..3) } }
    data class Rejected(val reason: String) : ActionTurnPlan
    data class Step(val request: ActionRequest, val sourceClause: String)

    companion object {
        fun parse(text: String, history: List<ChatEntry> = emptyList()): ActionTurnPlan {
            confirmation(text, history)?.let { request ->
                return Ready(listOf(Step(request, "Open " + request.arguments.getValue("app"))))
            }
            val clauses = ActionRequestText.actionClauses(text)
            if (clauses.isEmpty()) return NotAction
            // Do not apply action limits or condition rules to ordinary speech.
            if (clauses.none(::looksDirected)) return NotAction
            if (clauses.any { Regex("""(?i)\b(?:if|unless|after|when)\b""").containsMatchIn(it) })
                return Rejected("Conditional phone actions need a separate request.")
            if (clauses.size > 3) return Rejected("I can complete up to three phone actions at a time.")
            val parsed = clauses.map { it to requestFor(it) }
            val steps = parsed.map { (clause, request) ->
                request ?: return Rejected("I couldn't safely understand: $clause")
                Step(request, clause)
            }
            return Ready(steps)
        }

        private fun looksDirected(clause: String): Boolean =
            ActionRequestText.appTarget(clause) != null ||
                Regex("""(?i)^(?:set|make|turn|adjust|change|raise|lower|increase|decrease)\b.*\bvolume\b""").containsMatchIn(clause) ||
                ActionRequestText.batteryRequest(clause)

        private fun confirmation(text: String, history: List<ChatEntry>): ActionRequest? {
            val normalized = text.trim()
            val generic = Regex("""(?i)^(?:(?:okay|ok|yes|sure)(?:\s+(?:thanks|thank you|can you|could you|please|do it|now|and))*|do it|go ahead|open it|open that)(?:\s+please)?[?.!]*$""")
                .matches(normalized)
            if (!generic) return null
            val last = history.lastOrNull() ?: return null
            val app = if (last.role == "Jarvis") ActionRequestText.offeredApp(last.text)
                else ActionRequestText.clauses(last.text).firstNotNullOfOrNull(ActionRequestText::appTarget)
            return app?.let { ActionRequest("open_app", mapOf("app" to it)) }
        }

        private fun requestFor(clause: String): ActionRequest? {
            ActionRequestText.appTarget(clause)?.let { return ActionRequest("open_app", mapOf("app" to it)) }
            val volume = Regex("""(?i)^(?:set|make|turn|adjust|change|raise|lower|increase|decrease)(?:\s+(?:the|my))?(?:\s+media)?\s+volume(?:\s+to)?\s+(.+)$""")
                .matchEntire(clause)?.groupValues?.get(1)
            if (volume != null) return parseExactVolume(volume)?.let { ActionRequest("set_volume", mapOf("level" to it.toString())) }
            if (ActionRequestText.batteryRequest(clause)) return ActionRequest("read_battery")
            return null
        }

        /** Whole phrase grammar: no substring/last-number recovery. */
        private fun parseExactVolume(value: String): Int? {
            val raw = value.trim().lowercase().removeSuffix("%").trim().removeSuffix("percent").trim()
            raw.toIntOrNull()?.let { return it.takeIf { level -> level in 0..100 } }
            if (raw.contains(Regex("[^a-z -]")) || raw.startsWith("negative") || raw.contains("hundred") && raw != "one hundred") return null
            val units = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
                "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
            val simple = mapOf("zero" to 0, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
                "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
                "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
                "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "one hundred" to 100)
            simple[raw]?.let { return it }
            units[raw]?.let { return it }
            val words = raw.split(Regex("[ -]+"))
            val tens = simple.filterValues { it in 20..90 && it % 10 == 0 }
            return if (words.size == 2 && tens.containsKey(words[0]) && units.containsKey(words[1])) tens.getValue(words[0]) + units.getValue(words[1]) else null
        }
    }
}
