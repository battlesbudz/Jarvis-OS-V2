package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Checks complete speech phrases before publication; never retracts already-spoken text. */
class VoiceRepetitionGuard(user: String, previousReply: String?, private val emit: (String) -> Unit) {
    private val references = (listOf(user, previousReply.orEmpty()) +
        previousReply.orEmpty().split(Regex("[.!?\\n]+")))
        .filter { it.isNotBlank() }.toMutableList()
    private val pending = StringBuilder()
    private val accepted = StringBuilder()
    private var received = false
    var suppressedSentences = 0
        private set
    val text: String get() = accepted.toString()

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        received = true
        pending.append(chunk)
        while (true) {
            val end = pending.indexOfAny(charArrayOf('.', '!', '?', '\n'))
            if (end < 0) break
            // Keep decimal numbers intact when the following digit is already available.
            if (pending[end] == '.' && end > 0 && pending[end - 1].isDigit()) {
                if (end == pending.lastIndex) break
                if (pending[end + 1].isDigit()) {
                    // Decimal-containing phrases are checked at finish, preserving their factual value.
                    break
                }
            }
            val sentence = pending.substring(0, end + 1)
            pending.delete(0, end + 1)
            publish(sentence)
        }
        if (pending.length > 4096) { publish(pending.toString()); pending.clear() }
    }
    fun finish(finalText: String? = null): String {
        if (!received && finalText != null) accept(finalText)
        if (pending.isNotBlank()) publish(pending.toString())
        pending.clear()
        return text
    }
    private fun publish(candidate: String) {
        val phrase = candidate.trim()
        if (phrase.isBlank()) return
        if (references.any { duplicates(phrase, it) }) { suppressedSentences++; return }
        val output = (if (accepted.isEmpty()) "" else " ") + phrase
        accepted.append(output)
        references += phrase
        emit(output)
    }
    companion object {
        private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        private fun grams(words: List<String>, size: Int) = words.windowed(size).map { it.joinToString(" ") }.toSet()
        fun duplicates(candidate: String, reference: String): Boolean {
            val a = words(candidate)
            val b = words(reference)
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b) return true
            val an = a.joinToString(" ")
            val bn = b.joinToString(" ")
            if (a.size >= 3 && (" $bn ").contains(" $an ")) return true
            if (b.size >= 6 && (" $an ").contains(" $bn ")) return true
            if (a.size < 8 || b.size < 8) return false
            if (grams(a, 12).intersect(grams(b, 12)).isNotEmpty()) return true
            val candidateGrams = grams(a, 4)
            return candidateGrams.intersect(grams(b, 4)).size.toDouble() / candidateGrams.size >= .60
        }
    }
}
