package com.battlesbudz.jarvis.v2.voice

/** Longer linguistic context, with a hard bound before a single VITS model invocation. */
internal class PiperTextStream(private val waitForEnd: Boolean = false, private val openingTargetChars: Int = TARGET_CHARS) {
    init { require(openingTargetChars in 1..TARGET_CHARS) }
    private val buffer = StringBuilder()
    private var opening = true
    fun append(text: String) { buffer.append(text) }

    fun take(final: Boolean = false): String? {
        if (buffer.isBlank()) { if (final) buffer.clear(); return null }
        if (waitForEnd && !final) return null
        if (final && buffer.length <= MAX_CHARS) return release(buffer.length)
        var lastSentence = -1
        for (i in 0 until minOf(buffer.length, MAX_CHARS)) {
            if (buffer[i] !in ".!?\n") continue
            var end = i + 1
            while (end < buffer.length && buffer[end] in "\"'’”)!?") end++
            if (end > MAX_CHARS || end == buffer.length && !final) continue
            if (end < buffer.length && !buffer[end].isWhitespace()) continue
            val word = buffer.substring(0, i).takeLastWhile { !it.isWhitespace() }.lowercase()
            if (buffer[i] == '.' && (word in ABBREVIATIONS || word.length == 1 && word[0].isLetter())) continue
            lastSentence = end
            if (!waitForEnd && end >= if (opening) openingTargetChars else TARGET_CHARS) return release(end)
        }
        if (buffer.length < MAX_CHARS) return null
        // Prefer a complete sentence, then a word boundary; never drop a run-on suffix.
        val boundary = if (lastSentence > 0) lastSentence else
            (MAX_CHARS - 1 downTo MAX_CHARS / 2).firstOrNull { buffer[it].isWhitespace() } ?: MAX_CHARS
        return release(boundary)
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
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "st", "e.g", "i.e")
    }
}
