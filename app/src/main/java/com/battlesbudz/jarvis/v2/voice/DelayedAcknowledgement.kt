package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** One cached acknowledgment. Once audible, finish it while answer PCM builds behind it. */
internal class DelayedAcknowledgement(private val log: (String) -> Unit = {}) {
    enum class Stage { PROCESSING, RECOGNIZING, GENERATING, SYNTHESIZING }
    private val requested = CompletableDeferred<String>()
    private val lock = Any()
    private val prepared = mutableMapOf<String, SpeechAudio>()
    private val answerReady = CompletableDeferred<Unit>()
    @Volatile private var stage = Stage.PROCESSING
    private var job: Job? = null
    fun updateStage(value: Stage) { stage = value }
    fun request(text: String = FillerPhrases.INITIAL) {
        if (requested.complete(text)) log("acknowledgement_requested text=$text policy=once_complete_clip")
    }
    fun prepare(audio: SpeechAudio) { synchronized(lock) { prepared[audio.text] = audio } }
    fun preparationFailed(text: String) { log("acknowledgement_cache_failed text=$text answer_unblocked=true") }
    fun start(scope: CoroutineScope, delayMs: Long = 700,
              requestPreparation: (String) -> Unit = {}, play: suspend (SpeechAudio) -> Unit) {
        require(delayMs >= 0)
        check(job == null)
        job = scope.launch {
            val initial = select<String?> {
                answerReady.onAwait { null }
                requested.onAwait { it }
            } ?: return@launch
            if (waitForAnswer(delayMs)) return@launch
            if (synchronized(lock) { initial !in prepared }) requestPreparation(initial)
            while (!answerReady.isCompleted) {
                val audio = synchronized(lock) { prepared[initial] }
                if (audio != null) {
                    log("acknowledgement_wait_filler stage=$stage gapMs=$delayMs text=${audio.text} maxPerAnswer=1")
                    withTimeoutOrNull(4000) { play(audio) }
                    return@launch
                }
                if (waitForAnswer(20)) return@launch
            }
        }
    }
    private suspend fun waitForAnswer(ms: Long): Boolean = answerReady.isCompleted ||
        withTimeoutOrNull(ms.coerceAtLeast(1)) { answerReady.await(); true } == true
    suspend fun answerReady() {
        answerReady.complete(Unit)
        // Native production continues on its own worker and bounded queue during this join.
        // Stop/call cancellation still cancels the clip immediately through the parent scope.
        job?.join()
    }
    suspend fun close() { job?.cancelAndJoin() }
}
