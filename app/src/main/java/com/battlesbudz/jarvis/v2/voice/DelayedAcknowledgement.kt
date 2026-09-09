package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*

/** One neutral cue per confirmed turn. An answer wins unless cue playback already began. */
internal class DelayedAcknowledgement(private val log: (String) -> Unit = {}) {
    private val requested = CompletableDeferred<String>()
    private val prepared = mutableMapOf<String, CompletableDeferred<SpeechAudio>>()
    private fun preparation(text: String) = synchronized(lock) { prepared.getOrPut(text) { CompletableDeferred() } }
    private val lock = Any()
    private var answer = false
    private var playing = false
    private var job: Job? = null
    fun request(text: String = "One moment.") { if (requested.complete(text)) log("acknowledgement_requested text=$text") }
    fun prepare(audio: SpeechAudio) { preparation(audio.text).complete(audio) }
    fun start(scope: CoroutineScope, delayMs: Long = 700, play: suspend (SpeechAudio) -> Unit) {
        job = scope.launch {
            val text = requested.await()
            val prepared = preparation(text)
            delay(delayMs)
            // A slow model load must not silently expire a confirmed turn's cue.
            // answerReady/close cancel this wait when it is no longer useful.
            if (!prepared.isCompleted) log("acknowledgement_waiting_for_cache")
            val audio = prepared.await()
            val allowed = synchronized(lock) { if (answer) false else { playing = true; true } }
            if (allowed) play(audio)
        }
    }
    suspend fun answerReady() {
        val cancel = synchronized(lock) { answer = true; !playing }
        if (cancel) { log("acknowledgement_skipped reason=answer_ready"); job?.cancel() }
        job?.join() // Finish the short cue instead of chopping its final word.
    }
    suspend fun close() { job?.cancelAndJoin() }
}
