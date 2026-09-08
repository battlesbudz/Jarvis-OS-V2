package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Confirm new user words without pausing the speaker to test for echo. */
class BargeInGate(private val stableMs: Long = 200) {
    enum class Action { WAIT, CONFIRM }
    private var candidate = ""
    private var candidateAt = 0L
    private var speechAt: Long? = null
    var confirmed = false
        private set

    fun update(speech: Boolean, playing: Boolean, nowMs: Long,
               transcript: String = "", spokenText: String = ""): Action {
        if (confirmed) return Action.CONFIRM
        if (speech) speechAt = nowMs
        if (!playing && speech) { confirmed = true; return Action.CONFIRM }
        val heard = words(transcript).takeLast(6)
        val echo = words(spokenText).toSet()
        val novel = heard.filter { it !in echo }
        val command = novel.any { it in setOf("stop", "pause", "cancel", "wait", "jarvis") }
        val credible = heard.isNotEmpty() && (command ||
            (novel.distinct().size >= 2 && novel.size * 2 >= heard.size))
        if (!credible || speechAt?.let { nowMs - it > 1000 } != false) {
            candidate = ""; return Action.WAIT
        }
        val key = heard.joinToString(" ")
        if (key != candidate) { candidate = key; candidateAt = nowMs; return Action.WAIT }
        if (nowMs - candidateAt < stableMs) return Action.WAIT
        confirmed = true
        return Action.CONFIRM
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}']+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
}
