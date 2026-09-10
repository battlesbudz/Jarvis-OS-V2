package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.*
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
) : LocalModelEngine, Closeable {
    private companion object { val initializationLock = Any() }
    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            cacheDir = cacheDir,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            visionBackend = if (visionEnabled) Backend.GPU() else null,
            audioBackend = if (audioEnabled) Backend.CPU() else null,
            maxNumImages = if (visionEnabled) 1 else null
        )
    )
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private val closed = AtomicBoolean(false)

    private var toolsEnabled = true
    suspend fun setToolsEnabled(enabled: Boolean): Boolean {
        if (toolsEnabled == enabled) return false
        toolsEnabled = enabled
        resetConversation()
        return true
    }

    private fun createConversation() =
        if (tools.isEmpty() || !toolsEnabled) {
            engine.createConversation()
        } else {
            engine.createConversation(
                ConversationConfig(
                    tools = tools.map { tool(it) },
                    automaticToolCalling = false
                )
            )
        }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize() {
        // The SDK reads this process-global flag during initialize(), not in Engine's constructor.
        // Serialize ALL adapter initializations and restore the default even on unsupported files.
        synchronized(initializationLock) {
            val previous = ExperimentalFlags.enableSpeculativeDecoding
            try {
                ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
                engine.initialize()
            } finally { ExperimentalFlags.enableSpeculativeDecoding = previous }
        }
        conversation = createConversation()
    }

    suspend fun resetConversation() {
        conversation?.close()
        conversation = createConversation()
    }

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithContents(Contents.of(prompt), onToken)

    suspend fun generate(
        prompt: String,
        imageBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithContents(
        Contents.of(Content.ImageBytes(imageBytes), Content.Text(prompt)),
        onToken
    )

    /**
     * Sends audio directly to the multimodal Gemma conversation.
     * The byte array should contain a supported audio file, preferably a
     * 16 kHz mono WAV for predictable on-device preprocessing.
     */
    suspend fun generateAudio(
        prompt: String,
        audioBytes: ByteArray,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithContents(
        Contents.of(Content.AudioBytes(audioBytes), Content.Text(prompt)),
        onToken
    )

    private suspend fun generateWithContents(
        contents: Contents,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(Message.user(contents), onToken)

    suspend fun sendToolResult(
        call: ToolCall,
        resultMessage: String,
        onToken: (String) -> Unit
    ): GenerationResult = generateWithMessage(
        Message.tool(Contents.of(Content.ToolResponse(call.name, resultMessage))),
        onToken
    )

    private suspend fun generateWithMessage(
        message: Message,
        onToken: (String) -> Unit
    ): GenerationResult {
        if (conversation == null && !closed.get()) conversation = createConversation()
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val output = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var streamEvents = 0
        val firstCallbackAt = java.util.concurrent.atomic.AtomicLong()
        var nativeSubmitMs: Long? = null

        val responses = Channel<Message>(Channel.UNLIMITED)
        val terminal = CompletableDeferred<Unit>()
        try {
            try {
            activeConversation.sendMessageAsync(message, object : MessageCallback {
                override fun onMessage(message: Message) {
                    firstCallbackAt.compareAndSet(0L, System.nanoTime())
                    responses.trySend(message)
                }
                override fun onDone() { terminal.complete(Unit); responses.close() }
                override fun onError(throwable: Throwable) {
                    terminal.complete(Unit)
                    responses.close(throwable)
                }
            })
            nativeSubmitMs = (System.nanoTime() - startedAt) / 1_000_000
            } catch (error: Throwable) {
                terminal.complete(Unit)
                throw error
            }
            for (response in responses) {
                response.toolCalls.forEach {
                    toolCalls += ToolCall(it.name, JSONObject(it.arguments).toString())
                }
                val messageText = response.toString()
                if (messageText.isNotEmpty()) {
                    firstTokenAt = firstTokenAt ?: System.nanoTime()
                    streamEvents++
                    output.append(messageText)
                    onToken(messageText)
                }
            }

        } finally {
            withContext(NonCancellable) {
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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { conversation?.close(); conversation = null }
            finally { if (engine.isInitialized()) engine.close() }
        }
    }
}
