package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*

/** One neutral cue per confirmed turn. An answer wins unless cue playback already began. */
internal class DelayedAcknowledgement {
    private val requested = CompletableDeferred<Unit>()
    private val prepared = CompletableDeferred<SpeechAudio>()
    private val lock = Any()
    private var answer = false
    private var playing = false
    private var job: Job? = null
    fun request() { requested.complete(Unit) }
    fun prepare(audio: SpeechAudio) { prepared.complete(audio) }
    fun start(scope: CoroutineScope, delayMs: Long = 700, play: suspend (SpeechAudio) -> Unit) {
        job = scope.launch {
            requested.await()
            delay(delayMs)
            val audio = withTimeoutOrNull(1500) { prepared.await() } ?: return@launch
            val allowed = synchronized(lock) { if (answer) false else { playing = true; true } }
            if (allowed) play(audio)
        }
    }
    suspend fun answerReady() {
        val cancel = synchronized(lock) { answer = true; !playing }
        if (cancel) job?.cancel()
        job?.join() // Finish the short cue instead of chopping its final word.
    }
    suspend fun close() { job?.cancelAndJoin() }
}
