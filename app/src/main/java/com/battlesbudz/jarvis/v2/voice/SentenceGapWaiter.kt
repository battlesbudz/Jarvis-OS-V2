package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** The audio writer alone owns the handoff: cached cues end before more answer PCM is written. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SentenceGapWaiter(
    private val delayMs: Long = 600,
    private val repeatMs: Long = 3500,
    private val pollMs: Long = 20,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(delayMs >= 0 && repeatMs > 0 && pollMs > 0) }
    suspend fun <T> receive(channel: ReceiveChannel<T>, boundaryDrained: () -> Boolean,
                           play: suspend () -> Unit): ChannelResult<T> = coroutineScope {
        var cue: Job? = null
        var silentSince: Long? = null
        var nextCueAt = Long.MAX_VALUE
        try {
            while (true) {
                // Biased select: already-ready answer PCM or channel completion always wins.
                val value = select<ChannelResult<T>?> {
                    channel.onReceiveCatching { it }
                    onTimeout(pollMs) { null }
                }
                if (value != null) return@coroutineScope value
                val now = nowMs()
                if (!boundaryDrained()) {
                    silentSince = null
                    cue?.cancelAndJoin()
                    cue = null
                    continue
                }
                if (silentSince == null) { silentSince = now; nextCueAt = now + delayMs }
                if (now >= nextCueAt && cue?.isActive != true) {
                    cue = launch { play() }
                    nextCueAt = now + repeatMs
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } finally {
            withContext(NonCancellable) { cue?.cancelAndJoin() }
        }
    }
}
