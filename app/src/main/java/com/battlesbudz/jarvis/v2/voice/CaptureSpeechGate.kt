package com.battlesbudz.jarvis.v2.voice

/** Reject weak VAD at the recent room floor, without imposing an absolute whisper cutoff. */
internal class CaptureSpeechGate {
    private val noise = ArrayDeque<Double>()
    var noiseFloorRms = 0.0
        private set

    fun accept(decision: SpeechDecision, rms: Double): SpeechDecision {
        // Learn only from confidently non-speech frames. Speech must not raise its own floor.
        if (decision.probability < 0.15f) {
            noise.addLast(rms)
            if (noise.size > 30) noise.removeFirst()
            noiseFloorRms = noise.sorted()[(noise.size - 1) / 5]
        }
        val nearFloor = noise.isNotEmpty() && rms <= noiseFloorRms.coerceAtLeast(1.0) * 1.8
        return if (decision.probability < 0.8f && nearFloor) SpeechDecision(false, 0f) else decision
    }
}
