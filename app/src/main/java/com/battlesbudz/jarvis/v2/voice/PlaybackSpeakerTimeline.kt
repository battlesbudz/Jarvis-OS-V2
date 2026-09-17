package com.battlesbudz.jarvis.v2.voice

/** Select negative speaker samples by observed playback head at microphone capture time.
 * Queued future audio and the beginning of an old passage are not current playback. */
internal class PlaybackSpeakerTimeline {
    private data class Passage(val start: Long, val pcm: ShortArray, val rate: Int) {
        val end get() = start + pcm.size
    }
    private data class Position(val at: Long, val head: Long)
    private val passages = ArrayDeque<Passage>()
    private val positions = ArrayDeque<Position>()
    private var everPlayed = false

    @Synchronized fun append(startFrame: Long, pcm: ShortArray, sampleRate: Int) {
        require(sampleRate > 0 && startFrame >= 0)
        passages.addLast(Passage(startFrame, pcm, sampleRate))
        // Retain references to existing bounded TTS passages, not extra full PCM copies.
        while (passages.size > 3) passages.removeFirst()
        everPlayed = true
    }

    @Synchronized fun observe(atMs: Long, head: Long) {
        if (positions.lastOrNull()?.let { atMs < it.at || head < it.head } == true) return
        positions.addLast(Position(atMs, head))
        while (positions.size > 300 || positions.first().at < atMs - 10_000) positions.removeFirst()
        val rate = passages.lastOrNull()?.rate ?: return
        while (passages.size > 1 && passages.first().end < head - rate * 8L) passages.removeFirst()
    }

    @Synchronized fun reference(captureEndMs: Long, audioMs: Int): PlaybackSpeakerReference? {
        if (!everPlayed) return null
        val position = positions.lastOrNull { it.at <= captureEndMs }
            ?: return PlaybackSpeakerReference.unavailable("no_capture_position")
        val age = captureEndMs - position.at
        if (age > 250) return PlaybackSpeakerReference.unavailable("stale_capture_position")
        val passage = passages.lastOrNull { it.start < position.head && it.end >= position.head }
            ?: return PlaybackSpeakerReference.unavailable("no_played_passage")
        val rate = passage.rate
        val end = position.head
        // Include a small earlier window for route/reverberation delay. Remain bounded
        // to the same trailing three seconds used for the microphone speaker check.
        val duration = (audioMs + 200).coerceIn(250, 3000)
        val start = (end - duration * rate / 1000).coerceAtLeast(0)
        val pcm = ShortArray((end - start).toInt())
        var copied = 0
        for (part in passages) {
            val from = maxOf(start, part.start)
            val to = minOf(end, part.end)
            if (to <= from) continue
            if (part.rate != rate) return PlaybackSpeakerReference.unavailable("sample_rate_changed")
            part.pcm.copyInto(pcm, (from - start).toInt(), (from - part.start).toInt(), (to - part.start).toInt())
            copied += (to - from).toInt()
        }
        if (copied != pcm.size) return PlaybackSpeakerReference.unavailable("missing_played_samples")
        return PlaybackSpeakerReference.fromPcm(pcm, rate,
            "playback_head startFrame=$start endFrame=$end capturePositionAgeMs=$age referenceAudioMs=${pcm.size * 1000L / rate}")
            ?: PlaybackSpeakerReference.unavailable("silent_or_short_playback")
    }
}
