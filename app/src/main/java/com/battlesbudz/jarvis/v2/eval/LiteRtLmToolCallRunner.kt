package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ToolCallEngine

/**
 * On-device [ToolCallRunner] backed by a [ToolCallEngine] (production: LiteRtLmEngine).
 *
 * The engine must already be initialized with the catalog's OpenApiTool
 * declarations and tools enabled (setToolsEnabled(true)) before use. Each
 * utterance goes through the exact production conversation path (Conversation
 * API, automaticToolCalling=false); only the collected response.toolCalls are
 * returned. [generate] streams tokens that this runner ignores, and dispatch
 * happens elsewhere — this path never validates, gates, or executes anything.
 * Read-only with respect to tool effects.
 *
 * Every utterance starts from a reset native conversation: fixtures are
 * independent cases, and repeated generate calls on one retained conversation
 * would contaminate later cases with earlier turns.
 */
class LiteRtLmToolCallRunner(
    private val engine: ToolCallEngine
) : ToolCallRunner {
    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> {
        engine.resetConversation()
        return engine.generate(utterance) {}.toolCalls
    }
}
