package com.battlesbudz.jarvis.v2.voice

/** Longer linguistic context, with a hard bound before a single VITS model invocation. */
internal class PiperTextStream(private val waitForEnd: Boolean = false, private val openingTargetChars: Int = TARGET_CHARS,
                               private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(openingTargetChars in 1..TARGET_CHARS) }
    private val buffer = StringBuilder()
    private var opening = true
    private var firstTextAt: Long? = null
    fun append(text: String) {
        if (firstTextAt == null && text.isNotBlank()) firstTextAt = nowMs()
        buffer.append(text)
    }

    /** Only the selected faster-opening profile can release below its character target.
     * A deadline never cuts words, clauses or an incomplete sentence. */
    fun openingWaitMs(): Long? {
        if (!opening || waitForEnd || openingTargetChars >= TARGET_CHARS) return null
        if (sentenceEnds(false).none { it >= MIN_OPENING_CHARS }) return null
        return (OPENING_WAIT_MS - (nowMs() - (firstTextAt ?: return null))).coerceAtLeast(0)
    }

    fun take(final: Boolean = false): String? {
        if (buffer.isBlank()) { if (final) buffer.clear(); return null }
        if (waitForEnd && !final) return null
        if (final && buffer.length <= MAX_CHARS) return release(buffer.length)
        var lastSentence = -1
        val openingDue = openingWaitMs() == 0L
        for (end in sentenceEnds(final)) {
            lastSentence = end
            if (!waitForEnd && opening) return release(end)
            if (!waitForEnd && (end >= if (opening) openingTargetChars else TARGET_CHARS)) return release(end)
            if (openingDue && end >= MIN_OPENING_CHARS) return release(end)
        }
        if (buffer.length < MAX_CHARS) return null
        // Prefer a complete sentence, then a word boundary; never drop a run-on suffix.
        val boundary = if (lastSentence > 0) lastSentence else
            (MAX_CHARS - 1 downTo MAX_CHARS / 2).firstOrNull { buffer[it].isWhitespace() } ?: MAX_CHARS
        return release(boundary)
    }

    private fun sentenceEnds(final: Boolean): List<Int> {
        val ends = mutableListOf<Int>()
        for (i in 0 until minOf(buffer.length, MAX_CHARS)) {
            if (buffer[i] !in ".!?\n") continue
            var end = i + 1
            while (end < buffer.length && buffer[end] in "\"'’”)!?") end++
            if (end > MAX_CHARS) continue
            if (end < buffer.length && !buffer[end].isWhitespace()) continue
            val word = buffer.substring(0, i).takeLastWhile { !it.isWhitespace() }.lowercase()
            if (buffer[i] == '.' && (word in ABBREVIATIONS || word.length == 1 && word[0].isLetter())) continue
            // A token ending in a bare digit-period may be the beginning of a decimal split
            // across streamed callbacks. Keep it until the next token resolves it.
            if (!final && end == buffer.length && buffer[i] == '.' && word.all(Char::isDigit)) continue
            ends += end
        }
        return ends
    }

    private fun release(end: Int): String {
        opening = false
        val text = buffer.substring(0, end).trim()
        buffer.delete(0, end)
        while (buffer.isNotEmpty() && buffer[0].isWhitespace()) buffer.deleteCharAt(0)
        return text
    }

    companion object {
        const val TARGET_CHARS = 320
        const val MAX_CHARS = 640
        const val OPENING_WAIT_MS = 750L
        const val MIN_OPENING_CHARS = 60
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "st", "e.g", "i.e")
    }
}
