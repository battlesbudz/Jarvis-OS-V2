package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Owns throwaway generation only. No executor, speaker, or persistent memory is accessible here. */
class VoicePreparation(
    private val scope: CoroutineScope,
    private val generate: suspend (String, ByteArray, (String) -> Unit) -> GenerationResult,
    private val log: (String) -> Unit = {},
    private val coalesceMs: Long = 600
) {
    private data class Hypothesis(val text: String, val audio: ByteArray)
    private val updates = Channel<Hypothesis>(Channel.CONFLATED)
    private val transition = Mutex()
    @Volatile private var sealed = false
    private var draft: PreparedVoiceDraft? = null
    private val worker = scope.launch {
        for (initial in updates) {
            // Coalesce fast ASR changes, but do not wait for utterance silence.
            delay(coalesceMs)
            var latest = initial
            while (true) latest = updates.tryReceive().getOrNull() ?: break
            transition.withLock {
                if (sealed) return@withLock
                draft?.discard()
                if (!sealed) {
                    log("preparation_started chars=${latest.text.length}")
                    draft = PreparedVoiceDraft(scope, latest.text, log) { onToken ->
                        generate(latest.text, latest.audio, onToken)
                    }
                }
            }
        }
    }

    fun submit(text: String, audio: ByteArray) {
        if (!sealed && text.trim().split(Regex("\\s+")).size >= 3) {
            updates.trySend(Hypothesis(text, audio.copyOf()))
        }
    }

    suspend fun seal(finalTranscript: String): PreparedVoiceDraft? {
        check(!sealed) { "The voice transcript is already sealed." }
        sealed = true
        worker.cancelAndJoin()
        updates.close()
        return transition.withLock {
            val candidate = draft
            if (candidate != null && candidate.matches(finalTranscript) && !candidate.failed) {
                log("preparation_validated finalChars=${finalTranscript.length}")
                candidate.authorize(finalTranscript)
                candidate
            } else {
                candidate?.discard()
                log("preparation_discarded reason=final_transcript_changed_or_not_ready")
                null
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        sealed = true
        worker.cancelAndJoin()
        updates.cancel()
        transition.withLock { draft?.discard(); draft = null }
    }
}

class PreparedVoiceDraft internal constructor(
    scope: CoroutineScope,
    private val transcript: String,
    private val log: (String) -> Unit,
    generate: suspend ((String) -> Unit) -> GenerationResult
) {
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val result = CompletableDeferred<GenerationResult>()
    private var authorized = false
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var failed = false
        private set
    private val job = scope.launch {
        var bufferedCharacters = 0
        try {
            result.complete(generate { token ->
                bufferedCharacters += token.length
                check(bufferedCharacters <= 32_000) { "Prepared answer exceeded the mobile buffer budget." }
                chunks.trySend(token)
            })
        } catch (error: Throwable) {
            failed = true
            result.completeExceptionally(error)
            log("preparation_ended reason=${if (error is CancellationException) "superseded" else "generation_failed"}")
        } finally {
            chunks.close()
        }
    }

    fun matches(finalTranscript: String): Boolean = normalize(transcript) == normalize(finalTranscript)

    internal fun authorize(finalTranscript: String) {
        check(matches(finalTranscript))
        authorized = true
    }

    /** Called only after silence, final transcript validation, and final request routing. */
    suspend fun consume(onToken: (String) -> Unit): GenerationResult {
        check(authorized && consumed.compareAndSet(false, true)) { "Draft must be validated and consumed exactly once." }
        for (token in chunks) onToken(token)
        return result.await()
    }

    suspend fun discard() = withContext(NonCancellable) {
        job.cancelAndJoin()
        chunks.cancel()
    }

    private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
