package com.battlesbudz.jarvis.v2.voice

/** Preserve natural boundaries while allowing an early first clause from a token stream. */
class SpeechChunker {
    private val buffer = StringBuilder()
    private var first = true
    private var slow = false

    fun append(token: String) { buffer.append(token) }

    fun observe(realTimeFactor: Double) {
        if (realTimeFactor.isFinite() && realTimeFactor > 0) slow = realTimeFactor > 1.0
    }

    fun take(final: Boolean = false): String? {
        if (buffer.isBlank()) { if (final) buffer.clear(); return null }
        val target = if (first) 70 else if (slow) 120 else 180
        var boundary = -1
        for (i in buffer.indices) {
            val c = buffer[i]
            // Wait for whitespace after punctuation so decimals and token-split words stay intact.
            val followedBySpace = i + 1 < buffer.length && buffer[i + 1].isWhitespace()
            if ((c in ".!?" && followedBySpace) || c == '\n') {
                boundary = i + 1
                break
            }
            if (i >= target / 2 && c in ",;:" && followedBySpace && buffer.length >= target) {
                boundary = i + 1
                break
            }
            if (i >= target && c.isWhitespace()) { boundary = i; break }
        }
        if (boundary < 0) {
            if (!final) return null
            boundary = buffer.length
        }
        val result = buffer.substring(0, boundary).trim()
        buffer.delete(0, boundary)
        while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        if (result.isEmpty()) return take(final)
        first = false
        return result
    }
}
