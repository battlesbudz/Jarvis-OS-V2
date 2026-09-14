package com.battlesbudz.jarvis.v2.voice

/** Preserve native PCM exactly while bounding each playback queue entry to 400 ms. */
internal object KokoroPcmDelivery {
    fun forEachChunk(pcm: ShortArray, rate: Int, accept: (ShortArray, Boolean) -> Unit) {
        require(rate > 0)
        val size = (rate * 2 / 5).coerceAtLeast(1)
        for (offset in pcm.indices step size) {
            accept(pcm.copyOfRange(offset, minOf(offset + size, pcm.size)), offset == 0)
        }
    }

    // A character-budget or comma boundary must never trigger a spoken recovery cue.
    fun endsSentence(text: String): Boolean = text.trimEnd().trimEnd('"', '\'', '”', '’', ')')
        .lastOrNull() in listOf('.', '!', '?')
}
