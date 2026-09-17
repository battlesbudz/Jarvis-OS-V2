package com.battlesbudz.jarvis.v2.voice

/** Short speech must positively match the learned speaker before it can stop playback. */
internal object InterruptionSpeakerPolicy {
    enum class Decision { MATCH, DIFFERENT, UNCERTAIN, PLAYBACK_ECHO }
    fun decide(learned: Boolean, scores: List<Float>, playbackScores: List<Float> = emptyList(),
               playbackReferenceRequired: Boolean = false): Decision {
        val valid = scores.filter { it.isFinite() }
        if (!learned || valid.isEmpty()) return Decision.UNCERTAIN
        if (playbackReferenceRequired) {
            if (valid.size != scores.size || playbackScores.size != scores.size || playbackScores.any { !it.isFinite() })
                return Decision.UNCERTAIN
            // A learned centroid can be contaminated by earlier echo. An owner score
            // alone must never override evidence that the candidate is Piper's voice.
            if (scores.indices.any { scores[it] - playbackScores[it] < 0.08f }) return Decision.PLAYBACK_ECHO
        }
        if (valid.all { it >= 0.65f }) return Decision.MATCH
        return if (valid.all { it < 0.35f }) Decision.DIFFERENT else Decision.UNCERTAIN
    }
}
