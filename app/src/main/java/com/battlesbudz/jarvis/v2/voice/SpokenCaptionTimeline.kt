package com.battlesbudz.jarvis.v2.voice

import kotlin.math.sqrt

data class VoicePlaybackFrame(val caption: String = "", val level: Float = 0f)

/** Playback-clock captions, estimated by word length because TTS has no word timestamps.
 * Retains only queued phrase envelopes and a small rolling text window, never old PCM. */
class SpokenCaptionTimeline {
    private data class Phrase(val start: Long, val frames: Int, val rate: Int,
        val words: List<String>, val levels: List<Float>)
    private val phrases = ArrayDeque<Phrase>()
    private var previous = emptyList<String>()

    @Synchronized fun append(start: Long, rate: Int, pcm: ShortArray, text: String) {
        val block = (rate / 20).coerceAtLeast(1)
        val levels = (pcm.indices step block).map { start ->
            val end = (start + block).coerceAtMost(pcm.size)
            var squares = 0.0
            for (i in start until end) squares += pcm[i].toDouble() * pcm[i]
            (sqrt(squares / (end - start)) / 6000).toFloat().coerceIn(0f, 1f)
        }
        phrases.addLast(Phrase(start, pcm.size, rate, text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }, levels))
    }

    @Synchronized fun at(head: Long): VoicePlaybackFrame {
        if (head <= 0) return VoicePlaybackFrame()
        while (phrases.isNotEmpty() && head >= phrases.first().start + phrases.first().frames) {
            previous = (previous + phrases.removeFirst().words).takeLast(32)
        }
        val phrase = phrases.firstOrNull() ?: return VoicePlaybackFrame(previous.joinToString(" "))
        if (head < phrase.start) return VoicePlaybackFrame(previous.joinToString(" "))
        val offset = (head - phrase.start).toInt()
        val fraction = ((offset + phrase.rate * 0.18) / phrase.frames.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val total = phrase.words.sumOf { it.length.coerceAtLeast(3) }
        var weight = 0
        val visible = phrase.words.takeWhile { word ->
            val show = weight <= fraction * total
            weight += word.length.coerceAtLeast(3)
            show
        }
        val level = phrase.levels.getOrElse(offset / (phrase.rate / 20).coerceAtLeast(1)) { 0f }
        return VoicePlaybackFrame((previous + visible).takeLast(32).joinToString(" "), level)
    }
}
