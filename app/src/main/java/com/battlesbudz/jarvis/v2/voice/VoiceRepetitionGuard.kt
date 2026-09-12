package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Checks complete speech phrases before publication; never retracts already-spoken text. */
class VoiceRepetitionGuard(user: String, previousReply: String?, private val emit: (String) -> Unit) {
    private val references = (listOf(user, previousReply.orEmpty()) +
        (user + "\n" + previousReply.orEmpty()).split(Regex("[.!?\\n]+")))
        .filter { it.isNotBlank() }.toMutableList()
    private val pending = StringBuilder()
    private val accepted = StringBuilder()
    private var received = false
    /** Optional evidence/knowledge-gap policy, applied to complete sentences before publication. */
    var isPublishable: (String) -> Boolean = { true }
    private var policyRejected = false
    var suppressedSentences = 0
        private set
    var acceptedSentences = 0
        private set
    fun discardPending() { pending.clear() }
    val text: String get() = accepted.toString()
    val needsRepair: Boolean get() = suppressedSentences > 0 && text.isBlank()

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        received = true
        pending.append(chunk)
        while (true) {
            var end = -1
            for (i in pending.indices) {
                if (pending[i] !in ".!?\n") continue
                if (pending[i] == '.' && i > 0 && pending[i - 1].isDigit()) {
                    if (i == pending.lastIndex) break // Wait for a split decimal's next digit.
                    if (pending[i + 1].isDigit()) continue
                }
                end = i
                break
            }
            if (end < 0) break
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
        // If every streamed draft sentence failed policy, a final safe fallback may still be spoken.
        if (accepted.isEmpty() && policyRejected && finalText != null && isPublishable(finalText)) {
            publish(finalText)
        }
        return text
    }
    private fun publish(candidate: String) {
        val phrase = candidate.trim()
        if (phrase.isBlank()) return
        if (!isPublishable(phrase)) { policyRejected = true; return }
        if (references.any { duplicates(phrase, it) }) { suppressedSentences++; return }
        val output = (if (accepted.isEmpty()) "" else " ") + phrase
        accepted.append(output)
        acceptedSentences++
        references += phrase
        emit(output)
    }
    companion object {
        /** A checked phrase is complete; make its boundary visible to the speech chunker now. */
        fun speechReady(phrase: String): String = phrase.trim() + " "

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
