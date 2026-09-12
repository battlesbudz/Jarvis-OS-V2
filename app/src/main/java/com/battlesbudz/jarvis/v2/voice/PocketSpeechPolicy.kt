package com.battlesbudz.jarvis.v2.voice

/** Native audio frames control PCM delivery; text length never sets live Pocket chunk sizes. */
internal object PocketSpeechPolicy {
    const val VERSION = "streaming-voice-state-v5"
    fun sessionId(answer: String, index: Int, reset: Boolean) = if (reset) "$answer/segment-$index" else answer
    fun input(text: String, leadingPeriod: Boolean) = if (leadingPeriod) ". $text" else text
    const val SEED = 42
    fun extra(filler: Boolean = false, session: String? = null): Map<String, String> = mapOf(
        "temperature" to "0.7", "seed" to SEED.toString(),
        "first_chunk_size" to "3", "chunk_size" to "5", "max_reference_audio_len" to "15"
    ) + when {
        filler -> mapOf("max_frames" to "50")
        session != null -> mapOf("jarvis_session" to session)
        else -> emptyMap()
    }
}
