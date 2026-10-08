package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkSubmission
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkWarmState
import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Android adapter for a .litertlm model.
 *
 * Initialization and generation must be called from a background coroutine.
 * The model file is supplied by setup/model delivery and is never committed
 * to the repository.
 */
class LiteRtLmEngine(
    override val modelId: String,
    modelPath: String,
    cacheDir: String,
    useGpu: Boolean,
    private val tools: List<OpenApiTool> = emptyList(),
    val visionEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
    private val speculativeDecoding: Boolean? = null
) : LocalModelEngine, Closeable, ToolCallEngine {
    private companion object {
        val initializationLock = Any()
        val callbackDepth = ThreadLocal.withInitial { 0 }
        val quarantineLock = Any()
        val retainedNativeEngines = mutableSetOf<LiteRtLmEngine>()
        var quarantineAdmissionRetained = false
        fun checkNativeWorkerThread() {
            check(callbackDepth.get() == 0) { "Native lifecycle control is forbidden in model callbacks" }
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Native lifecycle control requires a worker thread" }
        }
        inline fun nativeCallback(block: () -> Unit) {
            callbackDepth.set(callbackDepth.get() + 1)
            try { block() } finally { callbackDepth.set(callbackDepth.get() - 1) }
        }
    }
    private val modelSpec = ModelCatalog.resolve(modelId)
    @OptIn(ExperimentalApi::class)
    private val engine = synchronized(initializationLock) {
        // The SDK reads this global flag while constructing the native engine.
        // Keep it scoped to our constructor and restore it on every exit.
        val previousBenchmark = ExperimentalFlags.enableBenchmark
        try {
            ExperimentalFlags.enableBenchmark = true
            Engine(EngineConfig(
                modelPath = modelPath,
                cacheDir = java.io.File(cacheDir, modelId).apply { mkdirs() }.path,
                backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                visionBackend = if (visionEnabled) Backend.GPU() else null,
                audioBackend = if (audioEnabled) Backend.CPU() else null,
                maxNumImages = if (visionEnabled) 1 else null,
                maxNumTokens = modelSpec.contextTokens
            ))
        } finally { ExperimentalFlags.enableBenchmark = previousBenchmark }
    }
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private val closed = AtomicBoolean(false)

    /**
     * Audio's native quarantine/history-rebuild lifecycle, ported verbatim
     * (see CheckedConversationLifecycle.kt). Its turn-level transitions
     * (beginTurn/finishTurn) are NOT wired into this engine's callback-channel
     * generate path on this branch — that restructuring is unification work.
     * Until then these properties always read false and the
     * ConversationModelSession guards are dormant. Do not treat them as
     * active protection on this branch.
     *
     * Wired vs not on this branch, stated plainly — no preservation is
     * claimed "by construction" from a shared interface; the
     * ConversationModelSession guards here are copies, and copies stay
     * dormant until they are wired. NOT wired: beginTurn/finishTurn
     * transitions, and awaitIdle (a no-op: Conversation exposes no
     * awaitIdle). Also absent on this branch: audio's DurableTaskRecovery
     * startup/alarm barrier and any AgentActivityMonitor production wiring.
     * Later unification must keep the protected audio engine and runtime
     * intact and add Muse-side features narrowly — never rewire or
     * reimplement the audio protections around the new code.
     */
    private val nativeLifecycle = CheckedConversationLifecycle(
        createConversation = ::createConversation,
        cancelConversation = { it.cancelProcess() },
        awaitIdle = { /* Conversation has no awaitIdle(); lifecycle guards dormant on this branch */ },
        closeConversation = { it.close() },
        closeEngine = { if (engine.isInitialized()) engine.close() },
        checkWorkerThread = ::checkNativeWorkerThread,
        onQuarantined = { retainProcessQuarantine() }
    )

    /**
     * Ordinary text jobs release their admission count in invokeOnCompletion too. Keep one
     * additional process reservation and the actual Engine strongly retained before that can
     * happen. This safety latch deliberately lasts until process exit, including after an
     * explicit disposal retry; it never permits a new inference or model-file replacement.
     */
    private fun retainProcessQuarantine() = synchronized(quarantineLock) {
        if (!quarantineAdmissionRetained) {
            ProcessConversationAdmission.activeJobs.incrementAndGet()
            quarantineAdmissionRetained = true
        }
        retainedNativeEngines.add(this)
        Unit
    }

    /** A terminal callback is never proof that the model lease can be released. */
    val nativeResourcesSafeToRelease: Boolean get() = nativeLifecycle.safeToRelease
    val isNativeQuarantined: Boolean get() = nativeLifecycle.isQuarantined
    val requiresConfirmedHistoryRebuild: Boolean get() = nativeLifecycle.requiresConfirmedHistoryRebuild

    /** Reports actual submissions, including incremental input, retries and recognition fallback. */
    var onPromptSubmitted: (String, Int) -> Unit = { _, _ -> }
    /** Native submission/raw-callback timing; intentionally carries no generated text. */
    var onInferenceProgress: (InferenceProgress) -> Unit = {}
    /** Classification and text-free completion observer; copied before every native submission. */
    var benchmarkPurpose: PipelineBenchmarkPurpose = PipelineBenchmarkPurpose.UNKNOWN
    var onBenchmarkSubmission: (PipelineBenchmarkSubmission) -> Unit = {}
    private val benchmarkNativeWorkStarted = AtomicBoolean(false)
    private var benchmarkInitializationMs: Long? = null
    private fun takeBenchmarkInitializationMs(): Long? = synchronized(this) {
        benchmarkInitializationMs.also { benchmarkInitializationMs = null }
    }

    private var nativeSession = 0L
    private var nativeSubmissions = 0
    fun inputContextDescription(): String =
        "nativeSession=$nativeSession nativePriorSubmissions=$nativeSubmissions toolsEnabled=$toolsEnabled"

    internal fun createVoicePrefillSession(): com.battlesbudz.jarvis.v2.voice.VoicePrefillSession {
        check(!closed.get())
        val purpose = benchmarkPurpose
        val sink = onBenchmarkSubmission
        val progressSink = onInferenceProgress
        if (!modelSpec.incrementalGemmaInput) {
            return TemplateVoiceSession { prompt, onToken ->
                setToolsEnabled(false)
                resetConversation()
                onPromptSubmitted(prompt, 0)
                generateWithContents(Contents.of(prompt), onToken, prompt.length, "text", purpose = purpose, sink = sink)
            }
        }
        // Voice owns this engine exclusively. Do not allocate a second idle KV cache.
        conversation?.close()
        conversation = null
        return LiteRtVoicePrefillSession(
            session = LiteRtNativeVoiceSession(engine.createSession()),
            benchmarkModelId = modelId,
            benchmarkPurpose = purpose,
            benchmarkSink = sink,
            benchmarkInitializationMs = takeBenchmarkInitializationMs(),
            onBenchmarkNativeWorkStarted = { benchmarkNativeWorkStarted.set(true) },
            onInferenceProgress = progressSink
        )
    }

    private var toolsEnabled = false
    override suspend fun setToolsEnabled(enabled: Boolean): Boolean {
        if (toolsEnabled == enabled) return false
        toolsEnabled = enabled
        resetConversation()
        return true
    }

    private fun createConversation() = engine.createConversation(
        modelConversationConfig(modelSpec, tools, toolsEnabled)
    )

    @OptIn(ExperimentalApi::class)
    override suspend fun initialize() {
        // The SDK reads this process-global flag during initialize(), not in Engine's constructor.
        // Serialize ALL adapter initializations and restore the default even on unsupported files.
        val initializationBeganAt = System.nanoTime()
        synchronized(initializationLock) {
            val previous = ExperimentalFlags.enableSpeculativeDecoding
            try {
                ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
                engine.initialize()
            } finally { ExperimentalFlags.enableSpeculativeDecoding = previous }
        }
        benchmarkInitializationMs = (System.nanoTime() - initializationBeganAt) / 1_000_000
        conversation = createConversation()
    }

    override suspend fun resetConversation() {
        nativeSession++
        nativeSubmissions = 0
        conversation?.close()
        // generateWithMessage recreates it when needed. Incremental voice uses its own Session.
        conversation = null
    }

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): GenerationResult {
        onPromptSubmitted(prompt, 0)
        return generateWithContents(Contents.of(prompt), onToken, prompt.length, "text")
    }

    suspend fun generate(
        prompt: String,
        imageBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult {
        require(visionEnabled && modelSpec.supportsVision) { "$modelId does not support image input. Select a vision model." }
        onPromptSubmitted(prompt, 0)
        return generateWithContents(Contents.of(Content.ImageBytes(imageBytes), Content.Text(prompt)), onToken,
            prompt.length, "image_text", imageBytes = imageBytes.size)
    }

    /**
     * Sends audio directly to the multimodal Gemma conversation.
     * The byte array should contain a supported audio file, preferably a
     * 16 kHz mono WAV for predictable on-device preprocessing.
     */
    suspend fun generateAudio(
        prompt: String,
        audioBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult {
        require(audioEnabled && modelSpec.supportsAudio) { "$modelId requires Moonshine or Whisper for speech recognition." }
        onPromptSubmitted(prompt, audioBytes.size)
        return generateWithContents(audioMessageContents(prompt, audioBytes), onToken,
            prompt.length, "audio_text", audioBytes = audioBytes.size)
    }

    private suspend fun generateWithContents(
        contents: Contents,
        onToken: (String) -> Unit,
        promptChars: Int,
        mode: String,
        audioBytes: Int = 0,
        imageBytes: Int = 0,
        purpose: PipelineBenchmarkPurpose = benchmarkPurpose,
        sink: (PipelineBenchmarkSubmission) -> Unit = onBenchmarkSubmission
    ): GenerationResult = generateWithMessage(Message.user(contents), onToken,
        promptChars, mode, audioBytes, imageBytes, purpose, sink)

    suspend fun sendToolResult(
        call: ToolCall,
        resultMessage: String,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(
        Message.tool(Contents.of(Content.ToolResponse(call.name, resultMessage))),
        onToken, resultMessage.length, "tool_response", purpose = PipelineBenchmarkPurpose.TOOL
    )

    /** Keep a batch in one native tool message so no call/result is discarded. */
    suspend fun sendToolResults(
        results: List<Pair<ToolCall, String>>,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(
        Message.tool(Contents.of(*results.map { Content.ToolResponse(it.first.name, it.second) }.toTypedArray())),
        onToken, results.sumOf { it.second.length }, "tool_response", purpose = PipelineBenchmarkPurpose.TOOL
    )

    private suspend fun generateWithMessage(
        message: Message,
        onToken: (String) -> Unit,
        promptChars: Int,
        mode: String,
        audioBytes: Int = 0,
        imageBytes: Int = 0,
        purpose: PipelineBenchmarkPurpose = benchmarkPurpose,
        sink: (PipelineBenchmarkSubmission) -> Unit = onBenchmarkSubmission
    ): GenerationResult {
        nativeSubmissions++
        if (conversation == null && !closed.get()) conversation = createConversation()
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val startedAt = System.nanoTime()
        val progressSink = onInferenceProgress
        val benchmark = NativeInferenceBenchmark(modelId, purpose,
            if (benchmarkNativeWorkStarted.compareAndSet(false, true)) PipelineBenchmarkWarmState.COLD else PipelineBenchmarkWarmState.WARM,
            mode, audioBytes, imageBytes, takeBenchmarkInitializationMs(), sink)
        var firstTokenAt: Long? = null
        val output = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var streamEvents = 0
        val firstCallbackAt = java.util.concurrent.atomic.AtomicLong()
        var nativeSubmitMs: Long? = null
        var benchmarkOutcome = PipelineBenchmarkOutcome.ERROR
        var benchmarkError: Throwable? = null
        var nativeTokens: NativeTokenTelemetry? = null

        val responses = Channel<Message>(Channel.UNLIMITED)
        val terminal = CompletableDeferred<Unit>()
        try {
            try {
            val submittedAt = System.nanoTime() / 1_000_000
            progressSink(InferenceProgress(submittedAtMs = submittedAt))
            val nativeSubmitBeganAt = System.nanoTime()
            try { activeConversation.sendMessageAsync(message, object : MessageCallback {
                override fun onMessage(message: Message) {
                    val callbackAt = System.nanoTime()
                    benchmark.measurement.callback(message.toString().isNotEmpty())
                    if (firstCallbackAt.compareAndSet(0L, callbackAt))
                        progressSink(InferenceProgress(firstRawTokenAtMs = callbackAt / 1_000_000))
                    responses.trySend(message)
                }
                override fun onDone() { benchmark.measurement.terminal(); terminal.complete(Unit); responses.close() }
                override fun onError(throwable: Throwable) {
                    benchmark.measurement.terminal()
                    terminal.complete(Unit)
                    responses.close(throwable)
                }
            }) } finally {
                benchmark.measurement.submitted(nativeSubmitBeganAt)
                nativeSubmitMs = (System.nanoTime() - nativeSubmitBeganAt) / 1_000_000
            }
            } catch (error: Throwable) {
                benchmark.measurement.terminal()
                terminal.complete(Unit)
                throw error
            }
            for (response in responses) {
                response.toolCalls.forEach {
                    toolCalls += ToolCall(it.name, JSONObject(it.arguments).toString())
                }
                val messageText = response.toString()
                if (messageText.isNotEmpty()) {
                    benchmark.measurement.visibleText()
                    firstTokenAt = firstTokenAt ?: System.nanoTime()
                    streamEvents++
                    output.append(messageText)
                    onToken(messageText)
                }
            }
            benchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE
            // Read only after the terminal callback and before closing/resetting this
            // exact conversation. Cancelled/error turns must not reuse prior counters.
            nativeTokens = readNativeTelemetry(activeConversation)
        } catch (error: Throwable) {
            benchmarkError = error
            benchmarkOutcome = if (error is CancellationException) PipelineBenchmarkOutcome.CANCELLED else PipelineBenchmarkOutcome.ERROR
            throw error
        } finally {
            try { withContext(NonCancellable) {
                if (!terminal.isCompleted) {
                    // The SDK's Flow awaitClose does not cancel native inference.
                    // Wait for the native terminal callback before freeing its conversation.
                    try {
                        activeConversation.cancelProcess()
                        withTimeout(10_000) { terminal.await() }
                    } finally {
                        activeConversation.close()
                        if (conversation === activeConversation) conversation = null
                    }
                }
                responses.cancel()
            } } finally {
                benchmark.finish(benchmarkOutcome, output.length, promptChars, streamEvents, error = benchmarkError,
                    nativeTokens = nativeTokens)
            }
        }

        val finishedAt = System.nanoTime()
        val firstTokenMs = firstTokenAt?.let { (it - startedAt) / 1_000_000 } ?: -1L
        val totalMs = (finishedAt - startedAt) / 1_000_000
        // LiteRT-LM currently exposes streamed text rather than token IDs on
        // Android. Four characters per token is a useful English estimate;
        // keep streamEvents separately so diagnostics remain honest.
        val estimatedTokens = output.toString().estimateTokenCount()
        val decodeMs = firstTokenAt?.let { finishedAt - it } ?: 0L
        return GenerationResult(
            text = output.toString(),
            timeToFirstTokenMs = firstTokenMs,
            decodeTokensPerSecond = if (decodeMs > 0 && estimatedTokens > 0) {
                estimatedTokens * 1_000.0 / (decodeMs / 1_000_000.0)
            } else null,
            outputTokens = estimatedTokens,
            totalGenerationTimeMs = totalMs,
            streamEvents = streamEvents,
            toolCalls = toolCalls,
            nativeSubmitMs = nativeSubmitMs,
            firstCallbackMs = firstCallbackAt.get().takeIf { it != 0L }?.let { (it - startedAt) / 1_000_000 }
        )
    }

    private fun String.estimateTokenCount(): Int =
        if (isBlank()) 0 else ((trim().length + 3) / 4).coerceAtLeast(streamEventsFallback())

    private fun String.streamEventsFallback(): Int =
        trim().split(Regex("\\s+")).count().coerceAtLeast(1)

    @OptIn(ExperimentalApi::class)
    private fun readNativeTelemetry(active: com.google.ai.edge.litertlm.Conversation): NativeTokenTelemetry? {
        val began = System.nanoTime()
        return runCatching {
            val measured = active.getBenchmarkInfo()
            NativeTokenTelemetry.checked(measured.lastPrefillTokenCount, measured.lastDecodeTokenCount,
                measured.timeToFirstTokenInSecond, measured.lastPrefillTokensPerSecond,
                measured.lastDecodeTokensPerSecond, (System.nanoTime() - began) / 1_000_000.0)
        }.getOrNull()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { conversation?.close(); conversation = null }
            finally { if (engine.isInitialized()) engine.close() }
        }
    }
}
