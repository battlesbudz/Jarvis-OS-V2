package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** One optional native owner. Capture never waits for inference or queues more work. */
class BoundedInterruptionRecognizer(
    private val scope: CoroutineScope,
    private val create: () -> StreamingTranscriber,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budgetMs: Long = 700,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
    private val hasBudget: () -> Boolean = { true }
) {
    data class Result(val revision: Long, val text: String, val audioAtMs: Long, val workMs: Long)
    private val result = AtomicReference<Result?>()
    @Volatile var unavailable: Boolean = false
        private set
    private var job: Job? = null
    private var closed = false
    val busy: Boolean get() = job?.isCompleted == false

    fun submit(revision: Long, pcm: ByteArray, audioAtMs: Long): Boolean {
        if (closed || unavailable || busy) return false
        require(pcm.size in 2..128_000 && pcm.size % 2 == 0)
        val owned = pcm.copyOf()
        result.set(null)
        job = scope.launch(dispatcher) {
            val started = nowMs()
            var asr: StreamingTranscriber? = null
            fun checkBudget() {
                ensureActive()
                check(hasBudget()) { "playback_budget" }
                check(nowMs() - started <= budgetMs) { "decode_budget" }
            }
            try {
                asr = create()
                checkBudget()
                asr.observeSpeech(true)
                // Bound work between cancellation/budget checks; native calls themselves
                // cannot be preempted. Never close their model from the capture thread.
                var offset = 0
                while (offset < owned.size) {
                    val end = minOf(offset + 8000, owned.size)
                    asr.accept(owned.copyOfRange(offset, end))
                    offset = end
                    checkBudget()
                }
                val text = TranscriptContent.speech(asr.finish())
                checkBudget()
                asr.close(); asr = null
                checkBudget()
                val workMs = (nowMs() - started).coerceAtLeast(0)
                result.set(Result(revision, text, audioAtMs, workMs))
                log("barge_probe_result revision=$revision audioMs=${owned.size / 32} workMs=$workMs chars=${text.length}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                unavailable = true
                log("barge_natural_unavailable reason=${error.message} fallback=keyword")
            } finally {
                runCatching { asr?.close() }.onFailure {
                    unavailable = true
                    log("barge_natural_unavailable reason=model_release fallback=keyword")
                }
            }
        }
        return true
    }
    fun poll(): Result? = result.getAndSet(null)
    suspend fun close() {
        closed = true
        withContext(NonCancellable) { job?.cancelAndJoin() }
        result.set(null)
    }
}
