package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Confirm new user words without pausing the speaker to test for echo. */
class BargeInGate(private val stableMs: Long = 300) {
    enum class Action { WAIT, CONFIRM }
    private var candidate = ""
    private var candidateAt = 0L
    private var speechAt: Long? = null
    var reason = "no_words"
        private set
    var confirmed = false
        private set

    fun update(speech: Boolean, playing: Boolean, nowMs: Long,
               transcript: String = "", spokenText: String = ""): Action {
        if (confirmed) return Action.CONFIRM
        if (speech) speechAt = nowMs
        val heard = words(transcript).takeLast(6)
        val echo = if (playing) words(spokenText).toSet() else emptySet()
        val novel = heard.filter { it !in echo }
        val command = heard.firstOrNull() in controls && heard.size <= 3 && novel.isNotEmpty()
        val requested = hasRequestIntent(heard)
        val credible = heard.isNotEmpty() && requested && (command ||
            (novel.distinct().size >= 2 && novel.size * 2 >= heard.size))
        if (!credible || speechAt?.let { nowMs - it > 1000 } != false) {
            reason = when {
                heard.isEmpty() -> "no_words"
                !requested -> "no_request_or_correction"
                !credible -> "echo_or_insufficient_new_words"
                else -> "no_recent_speech"
            }
            candidate = ""; return Action.WAIT
        }
        val key = heard.joinToString(" ")
        reason = "words_settling"
        if (key != candidate) { candidate = key; candidateAt = nowMs; return Action.WAIT }
        if (nowMs - candidateAt < stableMs) return Action.WAIT
        reason = "confirmed_new_request"
        confirmed = true
        return Action.CONFIRM
    }

    private fun hasRequestIntent(words: List<String>): Boolean {
        val content = words.dropWhile { it in setOf("please", "hey") }
        val first = content.firstOrNull() ?: return false
        if (first in controls) return true
        if (first in setOf("actually", "instead", "no")) return content.size >= 2
        if (content.take(2) in listOf(listOf("i", "mean"), listOf("i", "meant"), listOf("that's", "wrong"))) return true
        if (first in requests) return content.size >= 2
        return first in questions && content.size >= 3
    }

    private companion object {
        val controls = setOf("stop", "pause", "cancel", "wait", "jarvis")
        val requests = setOf("open", "set", "turn", "tell", "show", "find", "search", "read", "explain",
            "give", "play", "call", "message", "send", "navigate", "take", "check", "look", "help",
            "switch", "close", "launch", "repeat", "continue")
        val questions = setOf("what", "what's", "where", "where's", "when", "why", "who", "who's",
            "which", "how", "how's", "is", "are", "do", "does", "did", "can", "could", "would", "will")
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}']+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
}
