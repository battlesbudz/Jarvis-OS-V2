package com.battlesbudz.jarvis.v2.voice

/** One utterance; accept returns a replaceable hypothesis, finish seals dictation. */
interface StreamingTranscriber : AutoCloseable {
    fun accept(pcm: ByteArray): String
    fun finish(): String
    /** One independent full-clip pass after an empty streaming result; PCM stays in memory. */
    fun recover(pcm: ByteArray): String = ""
}
