package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.VoicePrefillSession
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Gemma 4 text-only session using v0.12.0 incremental prefill.
 * The catalog's pinned E2B/E4B artifacts use a Jinja template, with no legacy
 * Session prompt affixes. Session adds BOS; we supply the no-tool turn delimiters.
 * Recheck this contract before changing models or SDK (see voice pipeline docs).
 */
internal interface VoiceNativeCallback {
    fun onNext(response: String)
    fun onDone()
    fun onError(throwable: Throwable)
}

internal interface VoiceNativeSession : AutoCloseable {
    fun runPrefill(input: List<String>)
    fun generateContentStream(input: List<String>, callback: VoiceNativeCallback)
    fun cancelProcess()
}

internal class LiteRtVoicePrefillSession(private val session: VoiceNativeSession,
    private val benchmarkModelId: String? = null,
    private val benchmarkPurpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN,
    private val benchmarkSink: (PipelineBenchmarkSubmission) -> Unit = {},
    private val benchmarkInitializationMs: Long? = null,
    private val onBenchmarkNativeWorkStarted: () -> Unit = {},
    private val onInferenceProgress: (InferenceProgress) -> Unit = {}) : VoicePrefillSession {
    private var started = false
    private var closed = false
    private var decoding = false
    private var prefillNanos = 0L
    private var prefillChunks = 0
    private var promptChars = 0
    private var prefillFailure: Throwable? = null
    private var prefillReported = false
    override fun append(text: String) {
        check(!closed && !decoding)
        val chunk = (if (!started) "<|turn>user\n" else "") + text
        val began = System.nanoTime()
        promptChars += text.length
        prefillChunks++
        try {
            onBenchmarkNativeWorkStarted()
            session.runPrefill(listOf(chunk))
            started = true
        } catch (error: Throwable) {
            prefillFailure = error
            throw error
        } finally { prefillNanos += (System.nanoTime() - began).coerceAtLeast(0) }
    }
    override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
        check(started && !closed && !decoding)
        decoding = true
        // Native v0.12.0 GenerateContentStream calls RunPrefillAsync, which rejects
        // an empty input list. Submit the final turn boundary through that call,
        // exactly once, instead of prefilling it and requesting an empty decode.
        val finalInput = listOf("<turn|>\n<|turn>model\n")
        val began = System.nanoTime()
        val benchmark = NativeInferenceBenchmark(benchmarkModelId, benchmarkPurpose,
            PipelineBenchmarkWarmState.UNKNOWN, "incremental_text", initializationMs = benchmarkInitializationMs,
            sink = benchmarkSink)
        val tokens = Channel<String>(Channel.UNLIMITED)
        val terminal = CompletableDeferred<Unit>()
        var first: Long? = null
        val rawFirstReported = java.util.concurrent.atomic.AtomicBoolean(false)
        var events = 0
        var rawChars = 0
        var nativeSubmitMs: Long? = null
        val firstCallbackAt = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        var benchmarkOutcome = PipelineBenchmarkOutcome.ERROR
        var benchmarkError: Throwable? = null
        val text = StringBuilder()
        val visible = GemmaSessionText { chunk ->
            if (chunk.isNotEmpty()) benchmark.measurement.visibleText()
            text.append(chunk); onToken(chunk)
        }
        try {
            try {
                // This is the native submission, after final prefill has completed.
                onInferenceProgress(InferenceProgress(submittedAtMs = System.nanoTime() / 1_000_000))
                val nativeSubmitBeganAt = System.nanoTime()
                onBenchmarkNativeWorkStarted()
                try { session.generateContentStream(finalInput, object : VoiceNativeCallback {
                    override fun onNext(response: String) {
                        val callbackAt = benchmark.measurement.callback(response.isNotEmpty())
                        firstCallbackAt.compareAndSet(Long.MIN_VALUE, callbackAt)
                        if (response.isNotEmpty() && rawFirstReported.compareAndSet(false, true))
                            onInferenceProgress(InferenceProgress(firstRawTokenAtMs = System.nanoTime() / 1_000_000))
                        tokens.trySend(response)
                    }
                    override fun onDone() { benchmark.measurement.terminal(); terminal.complete(Unit); tokens.close() }
                    override fun onError(throwable: Throwable) { benchmark.measurement.terminal(); terminal.complete(Unit); tokens.close(throwable) }
                }) } finally {
                    benchmark.measurement.submitted(nativeSubmitBeganAt)
                    nativeSubmitMs = (System.nanoTime() - nativeSubmitBeganAt) / 1_000_000
                }
            } catch (error: Throwable) { benchmark.measurement.terminal(); terminal.complete(Unit); throw error }
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
            benchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE
        } catch (error: Throwable) {
            benchmarkError = error
            benchmarkOutcome = if (error is CancellationException) PipelineBenchmarkOutcome.CANCELLED else PipelineBenchmarkOutcome.ERROR
            throw error
        } finally {
            try { withContext(NonCancellable) {
                if (!terminal.isCompleted) {
                    session.cancelProcess()
                    terminal.await() // Never free a native session while callbacks still own it.
                }
                tokens.cancel()
            } } finally {
                benchmark.finish(benchmarkOutcome, text.length, promptChars, events,
                    prefillMs = prefillNanos / 1_000_000, prefillChunks = prefillChunks, error = benchmarkError)
            }
        }
        val ended = System.nanoTime()
        val estimatedTokens = (text.length + 3) / 4
        val decodeNs = first?.let { ended - it } ?: 0
        return GenerationResult(text.toString(), first?.let { (it - began) / 1_000_000 } ?: -1,
            if (decodeNs > 0) estimatedTokens * 1e9 / decodeNs else null,
            outputTokens = estimatedTokens, totalGenerationTimeMs = (ended - began) / 1_000_000,
            streamEvents = events, nativeSubmitMs = nativeSubmitMs,
            firstCallbackMs = firstCallbackAt.get().takeUnless { it == Long.MIN_VALUE }?.let { (it - began) / 1_000_000 })
    }
    override fun close() {
        if (!closed) {
            closed = true
            try { session.close() } finally {
                if (!decoding && !prefillReported && (prefillChunks > 0 || prefillFailure != null)) {
                    prefillReported = true
                    NativeInferenceBenchmark(benchmarkModelId, PipelineBenchmarkPurpose.DRAFT,
                        PipelineBenchmarkWarmState.UNKNOWN, "incremental_prefill_only",
                        initializationMs = benchmarkInitializationMs, sink = benchmarkSink).finish(
                        if (prefillFailure == null) PipelineBenchmarkOutcome.CANCELLED else PipelineBenchmarkOutcome.ERROR,
                        outputChars = 0, promptChars = promptChars, streamEvents = 0,
                        prefillMs = prefillNanos / 1_000_000, prefillChunks = prefillChunks, error = prefillFailure,
                        generationAttempted = false)
                }
            }
        }
    }
}
