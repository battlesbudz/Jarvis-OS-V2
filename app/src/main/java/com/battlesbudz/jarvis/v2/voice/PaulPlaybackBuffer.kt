package com.battlesbudz.jarvis.v2.voice

/** Raise the next reply's startup cushion after starvation; never count the final track drain. */
internal class PaulPlaybackBuffer {
    private var extraMs = 0
    @Synchronized fun target(baseMs: Int): Int = if (baseMs == 0) 0 else (baseMs + extraMs).coerceAtMost(600)
    @Synchronized fun observe(starved: Boolean) {
        extraMs = if (starved) (extraMs + 100).coerceAtMost(400) else (extraMs - 50).coerceAtLeast(0)
    }
}
