package com.battlesbudz.jarvis.v2.voice

/** Initial headroom from measured first-chunk speed, bounded for conversational latency. */
internal object PlaybackBufferPolicy {
    /** Keep pitch unchanged and choose one pace per answer, never varying it mid-sentence. */
    fun playbackSpeed(synthesisMs: Long, audioMs: Long): Float {
        if (synthesisMs <= 0 || audioMs <= 0) return 1f
        return (audioMs.toDouble() * 0.9 / synthesisMs).coerceIn(0.90, 1.0).toFloat()
    }

    fun startupWaitMs(synthesisMs: Long, audioMs: Long): Long {
        if (audioMs <= 0) return 0
        // Reserve 20% of playback time for scheduling variation. This is not a throughput fix.
        return (synthesisMs - audioMs * 4 / 5).coerceIn(0, 120)
    }
}
