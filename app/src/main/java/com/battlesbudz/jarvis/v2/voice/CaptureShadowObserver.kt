package com.battlesbudz.jarvis.v2.voice

/** Optional, failure-isolated observation only; cannot change endpoint or PCM ownership. */
interface CaptureShadowObserver {
    /** Borrowed stable PCM copy. An implementation may retain only its own bounded copy. */
    fun onPcm(pcm16: ByteArray, captureSampleBoundary: Long)
    fun onFrame(frame: CaptureShadowFrame)
    fun onPriority(reason: String)
    fun onInvalidated(reason: String)
    /** Nonblocking revocation; never wait for inference from capture/answer teardown. */
    fun close()
}

data class CaptureShadowFrame(
    val captureSampleBoundary: Long,
    val capturedAtNanos: Long,
    val hasSpeech: Boolean,
    val possibleSpeech: Boolean,
    val silenceMs: Long,
    val backlogMs: Long,
)
