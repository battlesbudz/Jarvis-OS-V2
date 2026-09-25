package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.*

/** Let each bundle use its own template; execution always stays with Jarvis's validated dispatcher. */
internal fun modelConversationConfig(spec: LocalModelSpec, tools: List<OpenApiTool>, enabled: Boolean): ConversationConfig {
    val declarations = if (enabled && spec.supportsTools) tools.map { tool(it) } else emptyList()
    return if (spec.incrementalGemmaInput) ConversationConfig(tools = declarations, automaticToolCalling = false)
    else ConversationConfig(
        tools = declarations,
        automaticToolCalling = false,
        channels = listOf(Channel("thought", "<think>", "</think>")),
        maxOutputToken = 512,
        thinkingConfig = ThinkingConfig(enableThinking = spec.reasoning, thinkingTokenBudget = 256)
    )
}
