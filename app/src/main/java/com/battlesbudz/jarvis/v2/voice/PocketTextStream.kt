package com.battlesbudz.jarvis.v2.voice

/** Text conditioning for a Pocket answer, independent of PCM chunk sizes.
 * Hold incomplete sentences so token boundaries, decimals and abbreviations don't restart prosody.
 * Release the first complete sentence immediately; later very short sentences may join their neighbor.
 * Like upstream, a single unusually long sentence is kept intact rather than cut by character count.
 */
internal class PocketTextStream(private val combineShort: Boolean = true) {
    private val pending = StringBuilder()
    private var emittedOpening = false
    fun append(text: String) { pending.append(text) }
    fun take(final: Boolean = false): String? {
        if (pending.isBlank()) { if (final) pending.clear(); return null }
        var boundary = -1
        for (i in pending.indices) {
            if (pending[i] == '\n') { boundary = i + 1; break }
            if (pending[i] !in ".!?") continue
            var end = i + 1
            while (end < pending.length && pending[end] in "\"'’”)!?") end++
            if (end == pending.length && !final) continue
            if (end < pending.length && !pending[end].isWhitespace()) continue
            val word = pending.substring(0, i).takeLastWhile { !it.isWhitespace() }.lowercase()
            if (pending[i] == '.' && (word in ABBREVIATIONS || word.length == 1 && word[0].isLetter())) continue
            boundary = end
            val prefixWords = pending.substring(0, end).trim().split(Regex("\\s+")).size
            if (combineShort && emittedOpening && prefixWords < 4 && (!final || end < pending.length)) { boundary = -1; continue }
            break
        }
        if (final && boundary < 0) boundary = pending.length
        if (boundary < 0) return null
        val text = pending.substring(0, boundary).trim()
        pending.delete(0, boundary)
        while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
        if (text.isNotEmpty()) emittedOpening = true
        return text.takeIf { it.isNotEmpty() } ?: take(final)
    }
    private companion object {
        val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "st", "e.g", "i.e")
    }
}
