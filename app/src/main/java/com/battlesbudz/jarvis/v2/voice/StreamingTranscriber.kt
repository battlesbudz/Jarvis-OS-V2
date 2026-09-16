package com.battlesbudz.jarvis.v2.voice

/** One utterance; accept returns a replaceable hypothesis, finish seals dictation. */
interface StreamingTranscriber : AutoCloseable {
    val noTextSilenceMs: Long get() = 3000
    val segmentSoftLimitMs: Long get() = 15_000
    val segmentHardLimitMs: Long get() = 22_000
    fun observeSpeech(speech: Boolean) {}
    /** Optional final-only mode for a bounded clip. Call before accept; finish must flush all audio. */
    fun prepareForBoundedProbe(maxAudioMs: Long): String = "streaming"
    fun accept(pcm: ByteArray): String
    /** Feed all PCM, but permit engines to omit intermediate decoding while catching up. */
    fun accept(pcm: ByteArray, allowPartial: Boolean): String = accept(pcm)
    fun finish(): String
    /** One independent full-clip pass after an empty streaming result; PCM stays in memory. */
    fun recover(pcm: ByteArray): String = ""
}
