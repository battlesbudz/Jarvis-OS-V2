package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Gap-only cached cues. Answer PCM cancels cache waits and any active cue, including the opening. */
internal class DelayedAcknowledgement(private val log: (String) -> Unit = {}) {
    enum class Stage(val cue: String?) {
        PROCESSING(null), RECOGNIZING("Listening to your recording."),
        GENERATING("Thinking."), SYNTHESIZING("Preparing the audio.")
    }
    private val requested = CompletableDeferred<String>()
    private val lock = Any()
    private val prepared = mutableMapOf<String, SpeechAudio>()
    private val answerReady = CompletableDeferred<Unit>()
    @Volatile private var stage = Stage.PROCESSING
    private var job: Job? = null
    fun updateStage(value: Stage) { stage = value }
    fun request(text: String = FillerPhrases.INITIAL) {
        if (requested.complete(text)) log("acknowledgement_requested text=$text policy=gap_only")
    }
    fun prepare(audio: SpeechAudio) { synchronized(lock) { prepared[audio.text] = audio } }
    fun preparationFailed(text: String) { log("acknowledgement_cache_failed text=$text answer_unblocked=true") }
    fun start(scope: CoroutineScope, delayMs: Long = 700, repeatGapMs: Long = 3500,
              requestPreparation: (String) -> Unit = {}, play: suspend (SpeechAudio) -> Unit) {
        require(delayMs >= 0 && repeatGapMs > 0)
        job = scope.launch {
            val initial = select<String?> {
                answerReady.onAwait { null }
                requested.onAwait { it }
            } ?: return@launch
            if (waitForAnswer(delayMs)) return@launch
            var turn = 0
            var last: String? = null
            var announcedStage: Stage? = null
            while (!answerReady.isCompleted) {
                val observed = stage
                val preferred = observed.cue?.takeIf { observed != announcedStage }
                    ?: if (turn == 0) initial else FillerPhrases.VARIATIONS[(turn - 1) % FillerPhrases.VARIATIONS.size]
                if (synchronized(lock) { preferred !in prepared }) requestPreparation(preferred)
                val audio = synchronized(lock) {
                    val candidates = listOf(preferred, observed.cue, initial) + FillerPhrases.VARIATIONS
                    candidates.filterNotNull().distinct().firstNotNullOfOrNull {
                        if (it != last) prepared[it] else null
                    }
                }
                if (audio != null && !answerReady.isCompleted) {
                    log("acknowledgement_wait_filler stage=$observed gapMs=${if (turn == 0) delayMs else repeatGapMs} text=${audio.text}")
                    coroutineScope {
                        val cue = launch { play(audio) }
                        try {
                            select<Unit> { answerReady.onAwait { }; cue.onJoin { } }
                        } finally {
                            withContext(NonCancellable) { cue.cancelAndJoin() }
                        }
                    }
                    last = audio.text
                    if (audio.text == observed.cue) announcedStage = observed
                }
                turn++
                if (waitForAnswer(repeatGapMs)) break
            }
        }
    }
    private suspend fun waitForAnswer(ms: Long): Boolean = answerReady.isCompleted ||
        withTimeoutOrNull(ms.coerceAtLeast(1)) { answerReady.await(); true } == true
    suspend fun answerReady() {
        answerReady.complete(Unit)
        job?.cancelAndJoin() // No obligatory um, native-cache wait, or audible tail before the answer.
    }
    suspend fun close() { job?.cancelAndJoin() }
}
