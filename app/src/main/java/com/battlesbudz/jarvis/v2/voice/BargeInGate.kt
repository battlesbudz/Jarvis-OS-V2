package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Confirm new user words without pausing the speaker to test for echo. */
class BargeInGate(private val stableMs: Long = 300) {
    enum class Action { WAIT, CONFIRM }
    private var candidate = ""
    private var candidateAt = 0L
    private var speechAt: Long? = null
    private var lastTranscript = ""
    private var lastReference = ""
    private var cachedRequest = emptyList<String>()
    var requestText = ""
        private set
    var reason = "no_words"
        private set
    var confirmed = false
        private set

    fun update(speech: Boolean, playing: Boolean, nowMs: Long,
               transcript: String = "", spokenText: String = ""): Action {
        if (confirmed) return Action.CONFIRM
        if (speech) speechAt = nowMs
        // Playback may underrun while its echo is still in ASR's rolling transcript.
        // Keep the reference in both phases and inspect clauses, not just the last words.
        val echoWords = words(spokenText)
        val echo = echoWords.toSet()
        if (transcript != lastTranscript || spokenText != lastReference) {
            cachedRequest = requestWords(transcript, echoWords)
            lastTranscript = transcript
            lastReference = spokenText
        }
        val heard = cachedRequest
        val novel = heard.filter { it !in echo }
        val command = heard.firstOrNull() in controls && heard.size <= 3 && novel.isNotEmpty()
        val requested = hasRequestIntent(heard)
        val credible = heard.isNotEmpty() && requested && (command ||
            (novel.distinct().size >= 2 && novel.size * 2 >= heard.size))
        if (!credible || speechAt?.let { nowMs - it > 1000 } != false) {
            reason = when {
                heard.isEmpty() -> if (transcript.isBlank()) "no_words" else "no_new_request_in_mixed_transcript"
                !requested -> "no_request_or_correction"
                !credible -> "echo_or_insufficient_new_words"
                else -> "no_recent_speech"
            }
            candidate = ""; return Action.WAIT
        }
        val key = heard.joinToString(" ")
        reason = "words_settling"
        if (key != candidate) {
            val extendsCandidate = candidate.isNotEmpty() && key.startsWith("$candidate ")
            candidate = key
            if (!extendsCandidate) { candidateAt = nowMs; return Action.WAIT }
        }
        if (nowMs - candidateAt < stableMs) return Action.WAIT
        reason = "confirmed_new_request"
        requestText = key
        confirmed = true
        return Action.CONFIRM
    }

    private fun requestWords(transcript: String, echo: List<String>): List<String> {
        for (clause in transcript.takeLast(1200).split(Regex("[.!?;\\n]+"))) {
            val tokens = words(clause)
            val matched = BooleanArray(tokens.size)
            // Remove contiguous echoed phrases, not a bag of common English words.
            for (i in tokens.indices) for (j in echo.indices) {
                var length = 0
                while (i + length < tokens.size && j + length < echo.size &&
                    tokens[i + length] == echo[j + length]) length++
                if (length >= 3) for (k in i until i + length) matched[k] = true
            }
            var i = 0
            while (i < tokens.size) {
                if (matched[i]) { i++; continue }
                val start = i
                while (i < tokens.size && !matched[i]) i++
                val run = tokens.subList(start, i).dropWhile { it in setOf("please", "hey") }
                // Only clause/echo boundaries start a request: "I can tell them" is incidental.
                if (hasRequestIntent(run)) {
                    val novel = run.filter { it !in echo }
                    val control = run.firstOrNull() in controls && run.size <= 3 && novel.isNotEmpty()
                    if (control || (novel.distinct().size >= 2 && novel.size * 2 >= run.size)) return run
                }
            }
        }
        return emptyList()
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
        val controls = setOf("stop", "pause", "cancel", "wait", "jarvis", "jervis", "goodbye")
        val requests = setOf("open", "set", "turn", "tell", "show", "find", "search", "read", "explain",
            "give", "play", "call", "message", "send", "navigate", "take", "check", "look", "help",
            "switch", "close", "launch", "repeat", "continue")
        val questions = setOf("what", "what's", "where", "where's", "when", "why", "who", "who's",
            "which", "how", "how's", "is", "are", "do", "does", "did", "can", "could", "would", "will")
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}']+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
}
