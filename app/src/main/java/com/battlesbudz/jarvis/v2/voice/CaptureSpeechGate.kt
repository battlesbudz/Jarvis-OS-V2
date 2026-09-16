package com.battlesbudz.jarvis.v2.voice

/** Reject weak VAD at the recent room floor, without imposing an absolute whisper cutoff. */
internal class CaptureSpeechGate {
    private data class Noise(val atMs: Long, val rms: Double)
    private val noise = ArrayDeque<Noise>()
    var noiseFloorRms = 0.0
        private set

    fun accept(decision: SpeechDecision, rms: Double, atMs: Long): SpeechDecision {
        // Age by captured audio time, even when no new low-VAD frames arrive.
        // A loud startup sample cannot remain the floor through a long utterance.
        while (noise.isNotEmpty() && atMs - noise.first().atMs > 3000) noise.removeFirst()
        // Learn only from confidently non-speech frames. Speech must not raise its own floor.
        if (decision.probability < 0.15f) {
            noise.addLast(Noise(atMs, rms))
            if (noise.size > 30) noise.removeFirst()
        }
        val calibrated = noise.size >= 3 && noise.last().atMs - noise.first().atMs >= 200
        noiseFloorRms = if (calibrated) noise.map { it.rms }.sorted()[(noise.size - 1) / 5] else 0.0
        val nearFloor = calibrated && rms <= noiseFloorRms.coerceAtLeast(1.0) * 1.8
        return if (decision.probability < 0.8f && nearFloor) SpeechDecision(false, 0f) else decision
    }
}
