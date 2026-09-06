package com.battlesbudz.jarvis.v2.ai

/**
 * Boundary for any local language-model runtime.
 * LiteRT-LM integration belongs behind this interface.
 */
interface LocalModelEngine {
    val modelId: String

    suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationResult
}

data class GenerationResult(
    val text: String,
    val timeToFirstTokenMs: Long,
    val decodeTokensPerSecond: Double?,
    val toolCalls: List<ToolCall> = emptyList()
)