package com.battlesbudz.jarvis.v2.voice

/** One utterance; accept returns a replaceable hypothesis, finish seals dictation. */
interface StreamingTranscriber : AutoCloseable {
    val noTextSilenceMs: Long get() = 3000
    fun observeSpeech(speech: Boolean) {}
    /** Optional final-only mode for a bounded clip. Call before accept; finish must flush all audio. */
    fun prepareForBoundedProbe(maxAudioMs: Long): String = "streaming"
    fun accept(pcm: ByteArray): String
    fun finish(): String
    /** One independent full-clip pass after an empty streaming result; PCM stays in memory. */
    fun recover(pcm: ByteArray): String = ""
}
