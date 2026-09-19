package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Carries the correction itself across turn cleanup, so the user need not repeat it. */
data class CapturedVoiceTurn(val transcript: String, val wav: ByteArray,
    val audioIsComplete: Boolean = true, val recognitionIssue: String? = null)
sealed interface ReplyOutcome<out T> {
    data class Finished<T>(val value: T) : ReplyOutcome<T>
    data class Interrupted(val correction: CapturedVoiceTurn) : ReplyOutcome<Nothing>
}

suspend fun <T> runInterruptibleReply(
    reply: suspend () -> T,
    listen: suspend (onConfirmed: () -> Unit) -> CapturedVoiceTurn,
    stopReply: () -> Unit
): ReplyOutcome<T> = coroutineScope {
    val confirmed = CompletableDeferred<Unit>()
    val replyJob = async { reply() }
    val listener = async { listen { confirmed.complete(Unit) } }
    try {
        val interrupted = select<Boolean> {
            confirmed.onAwait { true }
            replyJob.onAwait { false }
        }
        // An already confirmed correction wins a simultaneous completion. Otherwise
        // release the keyword reader immediately: the retained microphone replays
        // post-playback audio into ordinary follow-up ASR, without a keyword-only wait.
        if (interrupted || confirmed.isCompleted) {
            replyJob.cancel(VoiceControlCancellation(VoiceControl.STOP_REPLY))
            stopReply()
            replyJob.join()
            ReplyOutcome.Interrupted(listener.await())
        } else ReplyOutcome.Finished(replyJob.await())
    } finally {
        withContext(NonCancellable) { listener.cancelAndJoin(); replyJob.cancelAndJoin() }
    }
}
