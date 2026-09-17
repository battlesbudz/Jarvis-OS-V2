package com.battlesbudz.jarvis.v2.voice

/** Bounded, in-memory samples of the actual synthesized voice; never microphone training data. */
class PlaybackSpeakerReference private constructor(internal val windows: List<FloatArray>, internal val description: String) {
    companion object {
        fun unavailable(reason: String) = PlaybackSpeakerReference(emptyList(), "unavailable:$reason")
        fun fromPcm(pcm: ShortArray, sampleRate: Int, description: String = "cue"): PlaybackSpeakerReference? {
            require(sampleRate > 0)
            val start = pcm.indexOfFirst { kotlin.math.abs(it.toInt()) >= 256 }
            if (start < 0 || pcm.size - start < sampleRate / 4) return null
            val count = minOf(48_000, ((pcm.size - start).toLong() * 16_000 / sampleRate).toInt())
            val samples = FloatArray(count) { i ->
                val position = start + i * sampleRate / 16_000.0
                val index = position.toInt()
                val next = minOf(index + 1, pcm.lastIndex)
                ((pcm[index] + (pcm[next] - pcm[index]) * (position - index)) / 32768.0).toFloat()
            }
            val windows = if (count <= 24_000) listOf(samples) else
                listOf(samples.copyOfRange(0, 24_000), samples.copyOfRange(count - 24_000, count))
            return PlaybackSpeakerReference(windows, description)
        }
    }
}
