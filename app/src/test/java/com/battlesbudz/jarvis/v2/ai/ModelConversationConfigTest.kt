package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import org.junit.Assert.*
import org.junit.Test

class ModelConversationConfigTest {
    @Test fun communityBundlesReceiveToolsWithoutGemmaTemplatesOrAutomaticExecution() {
        val tools = MobileActionToolDefinitions.all()
        listOf("Qwen3-1.7B", "Phi-4-mini-instruct", "Jan-nano", "Gemma-4-E2B-it").forEach { id ->
            val spec = requireNotNull(ModelCatalog.find(id))
            val active = modelConversationConfig(spec, tools, true)
            assertEquals(id, tools.size, active.tools.size)
            assertFalse(active.automaticToolCalling)
            val ordinaryChat = modelConversationConfig(spec, tools, false)
            assertTrue(ordinaryChat.tools.isEmpty())
            assertFalse(ordinaryChat.automaticToolCalling)
        }
    }
}
