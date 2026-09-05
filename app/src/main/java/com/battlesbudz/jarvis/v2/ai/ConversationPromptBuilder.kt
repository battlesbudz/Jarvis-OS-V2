package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext

class ConversationPromptBuilder(
    private val shortTermContext: ShortTermConversationContext
) {
    fun buildGemmaPrompt(
        userPrompt: String,
        actionResultContext: String?,
        history: List<ChatEntry>,
        seedContext: Boolean
    ): String {
        val actionContext = actionResultContext?.let { "\n\n$it" }.orEmpty()
        val sessionContext = if (seedContext) {
            shortTermContext.promptContext(history.map { it.role to it.text })
                .takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
        } else ""
        return """
            You are Jarvis, a private local assistant. Answer the current
            user message directly and naturally. Do not list your capabilities,
            describe your tools, or discuss the routing system unless the user
            explicitly asks about them. Use a phone action only when the current
            request actually asks you to inspect or change the phone. Tool calls
            are handled internally by the app. Never emit <|tool_call>,
            <start_function_call>, call:, or any other tool-call markup in your
            user-facing answer. If a verified tool result is included below,
            treat it as authoritative and explain it naturally. Do not claim that
            you accessed or verified sources unless reference evidence is included
            in this prompt.
            For historical or factual questions, do not fill gaps by guessing.
            If you are not confident and no reference evidence is included,
            begin with [NEEDS_WIKIPEDIA], say that you do not know, and offer:
            "Would you like me to search Wikipedia?" Do not claim Wikipedia or
            Wikidata was searched unless evidence is included below.
            Prefer a concise answer that fits the current mobile turn budget.
            For broad requests, give the key points first and avoid unnecessary
            repetition or a long preamble.
            
            $sessionContext

            Current user message:
            $userPrompt
            $actionContext
        """.trimIndent()
    }

    fun buildToolResultContext(
        userPrompt: String,
        toolName: String,
        resultMessage: String,
        succeeded: Boolean
    ): String {
        return """
            MobileActions tool execution context:
            - User request: $userPrompt
            - Selected tool: $toolName
            - Execution status: ${if (succeeded) "succeeded" else "failed"}
            - Android result: $resultMessage
        """.trimIndent()
    }


}
