package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.flow.collect
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
    private val visionEnabled: Boolean = false
) : LocalModelEngine, Closeable {
    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            cacheDir = cacheDir,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            visionBackend = if (visionEnabled) Backend.GPU() else null,
            maxNumImages = if (visionEnabled) 1 else null
        )
    )
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private val closed = AtomicBoolean(false)

    private fun createConversation() =
        if (tools.isEmpty()) {
            engine.createConversation()
        } else {
            engine.createConversation(
                ConversationConfig(
                    tools = tools.map { tool(it) },
                    automaticToolCalling = false
                )
            )
        }

    suspend fun initialize() {
        engine.initialize()
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
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val output = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        activeConversation.sendMessageAsync(message).collect { response ->
            response.toolCalls.forEach {
                toolCalls += ToolCall(it.name, JSONObject(it.arguments).toString())
            }
            val messageText = response.toString()
            if (messageText.isNotEmpty()) {
                firstTokenAt = firstTokenAt ?: System.nanoTime()
                output.append(messageText)
                onToken(messageText)
            }
        }

        val firstTokenMs = firstTokenAt?.let { (it - startedAt) / 1_000_000 } ?: -1L
        return GenerationResult(
            text = output.toString(),
            timeToFirstTokenMs = firstTokenMs,
            decodeTokensPerSecond = null,
            toolCalls = toolCalls
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            conversation?.close()
            engine.close()
        }
    }
}


data class ToolCall(val name: String, val arguments: String)
