package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Every confirmed turn gets an opening cue; bounded silent intervals until answer PCM arrives. */
internal class DelayedAcknowledgement(private val log: (String) -> Unit = {}) {
    private val requested = CompletableDeferred<String>()
    private val lock = Any()
    private val prepared = mutableMapOf<String, CompletableDeferred<SpeechAudio?>>()
    private fun preparation(text: String) = synchronized(lock) { prepared.getOrPut(text) { CompletableDeferred() } }
    private val answerReady = CompletableDeferred<Unit>()
    private var job: Job? = null
    fun request(text: String = FillerPhrases.INITIAL) {
        if (requested.complete(text)) log("acknowledgement_requested text=$text policy=always_initial")
    }
    fun prepare(audio: SpeechAudio) { preparation(audio.text).complete(audio) }
    fun preparationFailed(text: String) { preparation(text).complete(null) }
    fun start(scope: CoroutineScope, delayMs: Long = 0, repeatGapMs: Long = 2500,
              play: suspend (SpeechAudio) -> Unit) {
        require(repeatGapMs > 0)
        job = scope.launch {
            val text = requested.await()
            delay(delayMs)
            val pending = preparation(text)
            if (!pending.isCompleted) log("acknowledgement_waiting_for_cache")
            val initial = pending.await()
            if (initial == null) {
                log("acknowledgement_skipped reason=cache_failed answer_unblocked=true")
                return@launch
            }
            play(initial) // Intentionally precedes even a fast answer, per the selected voice policy.
            while (!answerReady.isCompleted) {
                val answered = withTimeoutOrNull(repeatGapMs) { answerReady.await(); true } == true
                if (answered || answerReady.isCompleted) break
                val next = preparation(FillerPhrases.FOLLOWUP)
                val audio = if (next.isCompleted) next.await() ?: initial else initial
                if (answerReady.isCompleted) break
                log("acknowledgement_wait_filler silenceTargetMs=$repeatGapMs text=${audio.text}")
                // Follow-up filler must not reserve the speaker after real answer PCM is ready.
                coroutineScope {
                    val followup = launch { play(audio) }
                    try {
                        select<Unit> {
                            answerReady.onAwait {
                                log("acknowledgement_followup_yielded reason=answer_pcm_ready")
                            }
                            followup.onJoin { }
                        }
                    } finally {
                        withContext(NonCancellable) { followup.cancelAndJoin() }
                    }
                }
            }
        }
    }
    suspend fun answerReady(initialText: String = FillerPhrases.INITIAL) {
        if (job == null) return // Benchmarks do not enable fillers.
        request(initialText) // Also covers a confirmed turn's early error/direct-completion response.
        answerReady.complete(Unit)
        job?.join() // Preserve the required initial cue; follow-up playback yields immediately.
    }
    suspend fun close() { job?.cancelAndJoin() }
}
