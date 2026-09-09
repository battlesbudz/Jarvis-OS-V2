package com.battlesbudz.jarvis.v2.voice

/** Preserve natural boundaries while allowing an early first clause from a token stream. */
class SpeechChunker(private val openingChars: Int = DEFAULT_OPENING_CHARS) {
    private val buffer = StringBuilder()
    private var first = true
    private var slow = false

    fun append(token: String) { buffer.append(token) }

    fun observe(realTimeFactor: Double) {
        if (realTimeFactor.isFinite() && realTimeFactor > 0) slow = realTimeFactor > 1.0
    }

    fun take(final: Boolean = false, maxChars: Int? = null): String? {
        if (buffer.isBlank()) { if (final) buffer.clear(); return null }
        val normalTarget = if (first) openingChars else if (slow) 120 else 180
        val target = maxChars?.let { minOf(normalTarget, it.coerceAtLeast(16)) } ?: normalTarget
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
            if (i >= target && c.isWhitespace()) {
                // Keep a short sentence tail with this phrase instead of synthesizing
                // an orphan such as "price." in a separate expensive native call.
                if (!final && buffer.length < i + 16) return null
                val end = (i + 24).coerceAtMost(buffer.length)
                val tailEnd = (i + 1 until end).firstOrNull { at ->
                    buffer[at] in ".!?" && (at + 1 == buffer.length && final ||
                        at + 1 < buffer.length && buffer[at + 1].isWhitespace())
                }
                boundary = tailEnd?.let { it + 1 } ?: i
                break
            }
        }
        if (boundary < 0) {
            if (!final) return null
            boundary = buffer.length
        }
        val result = buffer.substring(0, boundary).trim()
        buffer.delete(0, boundary)
        while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        if (result.isEmpty()) return take(final, maxChars)
        first = false
        return result
    }

    companion object { const val DEFAULT_OPENING_CHARS = 40 }
}
