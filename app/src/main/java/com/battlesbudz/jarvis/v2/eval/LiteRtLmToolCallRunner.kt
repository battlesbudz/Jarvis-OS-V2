package com.battlesbudz.jarvis.v2.eval

import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ToolCall

/**
 * On-device [ToolCallRunner] backed by a real [LiteRtLmEngine].
 *
 * The engine must already be initialized with the catalog's OpenApiTool
 * declarations and tools enabled (setToolsEnabled(true)) before use. Each
 * utterance goes through the exact production conversation path (Conversation
 * API, automaticToolCalling=false); only the collected response.toolCalls are
 * returned. [generate] streams tokens that this runner ignores, and dispatch
 * happens elsewhere — this path never validates, gates, or executes anything.
 * Read-only with respect to tool effects.
 */
class LiteRtLmToolCallRunner(
    private val engine: LiteRtLmEngine
) : ToolCallRunner {
    override suspend fun runUtterance(modelId: String, utterance: String): List<ToolCall> =
        engine.generate(utterance) {}.toolCalls
}
