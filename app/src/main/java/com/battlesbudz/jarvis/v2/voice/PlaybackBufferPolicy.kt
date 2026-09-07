package com.battlesbudz.jarvis.v2.voice

/** Initial headroom from measured first-chunk speed, bounded for conversational latency. */
internal object PlaybackBufferPolicy {
    fun startupWaitMs(synthesisMs: Long, audioMs: Long): Long {
        if (audioMs <= 0) return 0
        // Reserve 20% of playback time for scheduling variation. This is not a throughput fix.
        return (synthesisMs - audioMs * 4 / 5).coerceIn(0, 1200)
    }
}
