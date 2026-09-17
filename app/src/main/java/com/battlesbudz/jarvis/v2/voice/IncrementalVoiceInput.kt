package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One append-only native input track. No answer tokens or tool calls during listening. */
interface VoicePrefillSession : AutoCloseable {
    fun append(text: String)
    suspend fun decode(onToken: (String) -> Unit): GenerationResult
}

class IncrementalVoiceInput(
    scope: CoroutineScope,
    private val prefix: String,
    private val create: () -> VoicePrefillSession,
    private val canPrefill: () -> Boolean = { true },
    private val log: (String) -> Unit = {}
) {
    private val updates = Channel<String>(Channel.CONFLATED)
    private var session: VoicePrefillSession? = null
    private var committed = ""
    private var previous = ""
    private var revised = false
    private var failure: Throwable? = null
    private var sealed = false
    private var consumed = false
    private var closed = false
    private var chunks = 0
    private var prefillMs = 0L
    private val worker = scope.launch(Dispatchers.Default) {
        for (text in updates) {
            log("input_partial chars=${text.length}")
            if (revised || failure != null || text.isBlank() || text.length > 12_000) continue
            if (!text.startsWith(committed)) {
                revised = true
                log("input_revision committedChars=${committed.length} policy=rebuild_once_at_final")
                continue
            }
            val common = previous.commonPrefixWith(text)
            previous = text
            // ASR can revise its newest word. Commit only complete words shared by
            // consecutive hypotheses; neither speech resumption nor appended words reset KV state.
            val stable = common.substring(0, (common.lastIndexOf(' ') + 1).coerceAtLeast(0))
            if ((stable.length <= committed.length && session != null) || !canPrefill()) continue
            try {
                val began = System.nanoTime()
                val native = session ?: create().also { session = it; it.append(prefix) }
                ensureActive()
                val appendedWords = stable.length > committed.length
                if (appendedWords) {
                    native.append(stable.substring(committed.length))
                    committed = stable
                    chunks++
                }
                prefillMs += (System.nanoTime() - began) / 1_000_000
                val event = if (appendedWords) "input_prefilled" else "input_context_prefilled"
                log("$event chunks=$chunks contextChars=${prefix.length} committedChars=${committed.length} prefillMs=$prefillMs mode=text decodeStarted=false")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = error
                log("input_prefill_failed type=${error.javaClass.simpleName} recovery=final_text")
            }
        }
    }

    fun submit(text: String) {
        if (!sealed && !closed) updates.trySend(text)
    }

    /** Finish queued prefill; never cancel and regenerate just because the user stops talking. */
    suspend fun seal() {
        if (sealed) return
        sealed = true
        updates.close()
        worker.join()
    }

    suspend fun answer(finalPrompt: String, onToken: (String) -> Unit): GenerationResult {
        seal()
        check(!closed && !consumed)
        consumed = true
        val retained = prefix + committed
        val reuse = session != null && !revised && failure == null && finalPrompt.startsWith(retained)
        if (!reuse) {
            session?.close(); session = null
            committed = ""
        }
        val native = session ?: create().also { session = it }
        log("input_finalized reuse=$reuse chunks=$chunks retainedChars=${if (reuse) retained.length else 0} " +
            "finalPromptChars=${finalPrompt.length} listeningPrefillMs=$prefillMs audioSubmitted=false")
        val remaining = if (reuse) finalPrompt.substring(retained.length) else finalPrompt
        val finalStarted = System.nanoTime()
        if (remaining.isNotEmpty()) native.append(remaining)
        log("input_final_prefill remainingChars=${remaining.length} finalPrefillMs=${(System.nanoTime() - finalStarted) / 1_000_000}")
        return native.decode(onToken)
    }

    /** Recovery is permitted only before user-visible output; never swallow cancellation. */
    suspend fun answerWithTextFallback(
        finalPrompt: String,
        onToken: (String) -> Unit,
        retry: suspend (Exception) -> GenerationResult
    ): GenerationResult {
        var outputStarted = false
        try {
            return answer(finalPrompt) { token ->
                if (token.isNotBlank()) outputStarted = true
                onToken(token)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            close()
            if (outputStarted) throw error
            return retry(error)
        } finally { close() }
    }

    suspend fun close() = withContext(NonCancellable) {
        if (!closed) {
            closed = true
            updates.close()
            worker.cancelAndJoin() // Blocking native calls finish before the session can be freed.
            session?.close(); session = null
        }
    }
}
