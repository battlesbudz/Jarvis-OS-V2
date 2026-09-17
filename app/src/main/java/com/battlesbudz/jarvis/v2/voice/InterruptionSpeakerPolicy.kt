package com.battlesbudz.jarvis.v2.voice

/** Short speech must positively match the learned speaker before it can stop playback. */
internal object InterruptionSpeakerPolicy {
    enum class Decision { MATCH, DIFFERENT, UNCERTAIN }
    fun decide(learned: Boolean, scores: List<Float>): Decision {
        val valid = scores.filter { it.isFinite() }
        if (!learned || valid.isEmpty()) return Decision.UNCERTAIN
        // Keep the existing preference clustering threshold; do not interpret absence
        // of a strong mismatch as proof that the owner spoke.
        if (valid.any { it >= 0.65f }) return Decision.MATCH
        return if (valid.all { it < 0.35f }) Decision.DIFFERENT else Decision.UNCERTAIN
    }
}
