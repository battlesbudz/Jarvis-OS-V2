package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

/** Called only at a completed sentence with another answer submission confirmed by the producer. */
internal class SentenceGapWaiter(
    private val lowWaterMs: Long = 250,
    private val headroomMs: Long = 640,
    private val pollMs: Long = 20
) {
    init { require(lowWaterMs >= 0 && headroomMs > lowWaterMs && pollMs > 0) }
    private var used = false
    suspend fun <T> receive(channel: ReceiveChannel<T>, remainingMs: () -> Long,
                           bufferedMs: () -> Long, allowed: () -> Boolean = { true },
                           play: suspend () -> Unit): ChannelResult<T> {
        if (used || !allowed()) return channel.receiveCatching()
        var pending: ChannelResult<T>? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (pending == null) {
                val next = channel.tryReceive()
                if (next.isClosed) return next
                if (next.isSuccess) pending = next
            }
            if (!allowed() || bufferedMs() >= headroomMs || remainingMs() > lowWaterMs) {
                if (pending != null) return pending
                if (!allowed()) return channel.receiveCatching()
            } else if (remainingMs() == 0L) {
                // No previous sentence audio remains: a separate static cue cannot overlap it.
                used = true
                withTimeoutOrNull(4000) { play() }
                // Keep a complete phrase, even if answer PCM arrived while it was playing.
                return pending ?: channel.receiveCatching()
            }
            delay(pollMs)
        }
    }
}
