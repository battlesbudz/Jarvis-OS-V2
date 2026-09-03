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
        val patterns = listOf(
            Regex("""<\|tool_call>\s*call:([^\{]+)\{(.*?)\}<\|tool_call\|>""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<start_function_call>\s*call:([^\{]+)\{(.*?)\}<end_function_call>""", RegexOption.DOT_MATCHES_ALL)
        )
        val argumentPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*):\s*(?:<escape>(.*?)<escape>|"([^"]*)"|([^,}]+))""")
        return patterns.asSequence()
            .flatMap { pattern -> pattern.findAll(text).asSequence() }
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
