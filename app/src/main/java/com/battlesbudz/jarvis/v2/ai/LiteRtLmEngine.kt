package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.flow.collect
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

    /**
     * Requests a structured FunctionGemma tool call without allowing the
     * runtime to execute it. Kotlin validates and executes the typed action.
     */
    suspend fun generateToolCalls(prompt: String): List<ToolCall> {
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val routingPrompt = """
            You are a model that can do function calling with the following functions.
            Select a function when the user's request requires a phone action.
            Return a structured function call instead of a natural-language answer.
            
            User request:
            $prompt
        """.trimIndent()
        val response = activeConversation.sendMessage(routingPrompt)
        val structuredCalls = response.toolCalls.map {
            ToolCall(name = it.name, arguments = it.arguments.toString())
        }
        return structuredCalls.ifEmpty { parseRawToolCalls(response.toString()) }
    }

    private fun parseRawToolCalls(text: String): List<ToolCall> {
        val callPattern = Regex(
            """(?s)(?:<\|)?tool_call>\s*call:([A-Za-z0-9_.:-]+)\s*\{(.*?)\}(?:<\|tool_call\|>)?"""
        )
        val functionPattern = Regex(
            """(?s)<start_function_call>\s*call:([A-Za-z0-9_.:-]+)\s*\{(.*?)\}<end_function_call>"""
        )
        val argumentPattern = Regex(
            """([A-Za-z_][A-Za-z0-9_]*):\s*(?:<escape>(.*?)<escape>|"([^"]*)"|([^,}]+))"""
        )
        return (callPattern.findAll(text).asSequence() + functionPattern.findAll(text).asSequence())
            .mapNotNull { match ->
                val rawName = match.groupValues[1].substringAfterLast(":").trim()
                if (rawName.isBlank()) return@mapNotNull null
                val arguments = JSONObject()
                argumentPattern.findAll(match.groupValues[2]).forEach { argument ->
                    val value = argument.groupValues.drop(2).firstOrNull { it.isNotBlank() }
                        ?.trim().orEmpty()
                    arguments.put(argument.groupValues[1], value)
                }
                ToolCall(rawName, arguments.toString())
            }
            .toList()
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
