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
    private val tools: List<OpenApiTool> = emptyList()
) : LocalModelEngine, Closeable {
    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            cacheDir = cacheDir,
            backend = if (useGpu) Backend.GPU() else Backend.CPU()
        )
    )
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private val closed = AtomicBoolean(false)

    suspend fun initialize() {
        engine.initialize()
        conversation = if (tools.isEmpty()) {
            engine.createConversation()
        } else {
            engine.createConversation(
                ConversationConfig(
                    tools = tools.map { tool(it) },
                    automaticToolCalling = false
                )
            )
        }
    }

    /**
     * Requests a structured FunctionGemma tool call without allowing the
     * runtime to execute it. Kotlin validates and executes the typed action.
     */
    suspend fun generateToolCalls(prompt: String): List<ToolCall> {
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        return activeConversation.sendMessage(prompt).toolCalls.map {
            ToolCall(name = it.name, arguments = it.arguments.toString())
        }
    }

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): GenerationResult {
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val output = StringBuilder()

        activeConversation.sendMessageAsync(prompt).collect { message ->
            val text = message.toString()
            if (text.isNotEmpty()) {
                firstTokenAt = firstTokenAt ?: System.nanoTime()
                output.append(text)
                onToken(text)
            }
        }

        val firstTokenMs = firstTokenAt?.let { (it - startedAt) / 1_000_000 } ?: -1L
        return GenerationResult(
            text = output.toString(),
            timeToFirstTokenMs = firstTokenMs,
            decodeTokensPerSecond = null
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
