package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter

/** Owns throwaway generation and one silent opening. No executor or playback permission. */
class VoicePreparation(
    private val scope: CoroutineScope,
    private val generate: suspend (String, ByteArray, (String) -> Unit) -> GenerationResult,
    private val log: (String) -> Unit = {},
    private val coalesceMs: Long = 300,
    private val prepareOpening: (String) -> PreparedSpeechOpening? = { null },
    private val speechText: (String) -> String = { it },
    private val sentenceOpenings: Boolean = false
) {
    private data class Hypothesis(val revision: Long, val text: String?, val audio: ByteArray)
    private val updates = Channel<Hypothesis>(Channel.CONFLATED)
    private val transition = Mutex()
    private val revision = AtomicLong()
    @Volatile private var sealed = false
    @Volatile private var draft: PreparedVoiceDraft? = null
    private var draftRevision = -1L
    private val worker = scope.launch {
        for (initial in updates) {
            // Coalesce fast ASR changes, but do not wait for utterance silence.
            if (initial.text != null) delay(coalesceMs)
            var latest = initial
            while (true) latest = updates.tryReceive().getOrNull() ?: break
            transition.withLock {
                if (sealed) return@withLock
                draft?.discard()
                draft = null
                val text = latest.text
                if (!sealed && text != null && latest.revision == revision.get()) {
                    log("preparation_started chars=${text.length} revision=${latest.revision}")
                    draftRevision = latest.revision
                    val created = PreparedVoiceDraft(scope, text, log, prepareOpening, speechText, sentenceOpenings) { onToken ->
                        generate(text, latest.audio, onToken)
                    }
                    draft = created
                    // submit()/speechResumed() may have invalidated us during construction.
                    if (latest.revision != revision.get()) created.invalidate()
                }
            }
        }
    }

    fun submit(text: String, audio: ByteArray) {
        if (!sealed) {
            val current = revision.incrementAndGet()
            draft?.invalidate()
            val candidate = text.takeIf { it.trim().split(Regex("\\s+")).size >= 3 }
            updates.trySend(Hypothesis(current, candidate, if (candidate != null) audio.copyOf() else byteArrayOf()))
        }
    }

    /** Invalidate immediately, even when resumed speech has not produced different ASR text yet. */
    fun speechResumed() {
        if (!sealed) {
            val current = revision.incrementAndGet()
            draft?.invalidate()
            updates.trySend(Hypothesis(current, null, byteArrayOf()))
            log("preparation_invalidated reason=speech_resumed revision=$current")
        }
    }

    suspend fun seal(finalTranscript: String): PreparedVoiceDraft? {
        check(!sealed) { "The voice transcript is already sealed." }
        sealed = true
        worker.cancelAndJoin()
        updates.close()
        return transition.withLock {
            val candidate = draft
            if (candidate != null && draftRevision == revision.get() && candidate.matches(finalTranscript) && !candidate.failed) {
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
    private val prepareOpening: (String) -> PreparedSpeechOpening? = { null },
    private val speechText: (String) -> String = { it },
    private val sentenceOpenings: Boolean = false,
    generate: suspend ((String) -> Unit) -> GenerationResult
) {
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val result = CompletableDeferred<GenerationResult>()
    private var authorized = false
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var opening: PreparedSpeechOpening? = null
    @Volatile var failed = false
        private set
    private val job = scope.launch {
        var bufferedCharacters = 0
        val openingChunker = SpeechChunker(sentenceMode = sentenceOpenings)
        var openingRequested = false
        fun requestOpening(text: String) {
            if (failed || openingRequested) return
            openingRequested = true
            val created = prepareOpening(text)
            opening = created
            if (failed) created?.discard()
            else if (consumed.get()) created?.authorize()
        }
        val filter = AssistantStreamFilter { token ->
            if (!openingRequested) {
                openingChunker.append(speechText(token))
                openingChunker.take()?.let(::requestOpening)
            }
        }
        try {
            val generated = generate { token ->
                bufferedCharacters += token.length
                check(bufferedCharacters <= 32_000) { "Prepared answer exceeded the mobile buffer budget." }
                chunks.trySend(token)
                filter.accept(token)
            }
            if (generated.toolCalls.isNotEmpty()) opening?.discard()
            else if (!openingRequested) openingChunker.take(final = true)?.let(::requestOpening)
            result.complete(generated)
        } catch (error: Throwable) {
            failed = true
            opening?.discard()
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
        opening?.authorize()
        for (token in chunks) onToken(token)
        return result.await()
    }

    suspend fun discard() = withContext(NonCancellable) {
        invalidate()
        job.cancelAndJoin()
        chunks.cancel()
    }

    fun invalidate() {
        failed = true
        opening?.discard()
        job.cancel()
    }

    // Final ASR punctuation can settle without changing the spoken request. Preserve
    // internal punctuation/numbers and all words, including a corrected action target.
    private fun normalize(text: String) = text.trim().trimEnd('.', '?', '!').trimEnd()
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
