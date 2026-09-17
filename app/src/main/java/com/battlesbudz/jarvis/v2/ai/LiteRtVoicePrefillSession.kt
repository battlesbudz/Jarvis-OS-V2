package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.VoicePrefillSession
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Gemma 4 text-only session using v0.12.0 incremental prefill.
 * The catalog's pinned E2B/E4B artifacts use a Jinja template, with no legacy
 * Session prompt affixes. Session adds BOS; we supply the no-tool turn delimiters.
 * Recheck this contract before changing models or SDK (see voice pipeline docs).
 */
internal class LiteRtVoicePrefillSession(private val session: Session) : VoicePrefillSession {
    private var started = false
    private var closed = false
    override fun append(text: String) {
        check(!closed)
        val chunk = (if (!started) "<|turn>user\n" else "") + text
        session.runPrefill(listOf(InputData.Text(chunk)))
        started = true
    }
    override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
        check(started && !closed)
        // Official Gemma 4 no-tool template: end user turn, then open model turn.
        session.runPrefill(listOf(InputData.Text("<turn|>\n<|turn>model\n")))
        val began = System.nanoTime()
        val tokens = Channel<String>(Channel.UNLIMITED)
        val terminal = CompletableDeferred<Unit>()
        var first: Long? = null
        var events = 0
        var rawChars = 0
        val text = StringBuilder()
        val visible = GemmaSessionText { chunk -> text.append(chunk); onToken(chunk) }
        try {
            try {
                session.generateContentStream(emptyList(), object : ResponseCallback {
                    override fun onNext(response: String) { tokens.trySend(response) }
                    override fun onDone() { terminal.complete(Unit); tokens.close() }
                    override fun onError(throwable: Throwable) { terminal.complete(Unit); tokens.close(throwable) }
                })
            } catch (error: Throwable) { terminal.complete(Unit); throw error }
            for (token in tokens) {
                if (token.isNotEmpty()) {
                    if (first == null) first = System.nanoTime()
                    events++
                    rawChars += token.length
                    check(rawChars <= 32_000) { "Voice response exceeded the output budget" }
                    visible.accept(token)
                }
            }
            visible.finish()
        } finally {
            withContext(NonCancellable) {
                if (!terminal.isCompleted) {
                    session.cancelProcess()
                    terminal.await() // Never free a native session while callbacks still own it.
                }
                tokens.cancel()
            }
        }
        val ended = System.nanoTime()
        val estimatedTokens = (text.length + 3) / 4
        val decodeNs = first?.let { ended - it } ?: 0
        return GenerationResult(text.toString(), first?.let { (it - began) / 1_000_000 } ?: -1,
            if (decodeNs > 0) estimatedTokens * 1e9 / decodeNs else null,
            outputTokens = estimatedTokens, totalGenerationTimeMs = (ended - began) / 1_000_000,
            streamEvents = events, firstCallbackMs = first?.let { (it - began) / 1_000_000 })
    }
    override fun close() {
        if (!closed) { closed = true; session.close() }
    }
}
