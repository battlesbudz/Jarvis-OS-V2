package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionGemmaActionDecoderTest {
    @Test
    fun decodesDirectArguments() {
        val request = FunctionGemmaActionDecoder.decode(
            ToolCall("set_volume", """{"level": 50}""")
        )
        assertEquals(ActionRequest("set_volume", mapOf("level" to "50")), request)
    }

    @Test
    fun decodesNestedArguments() {
        val request = FunctionGemmaActionDecoder.decode(
            ToolCall("open_app", """{"args":{"package":"com.android.settings"}}""")
        )
        assertEquals(
            ActionRequest("open_app", mapOf("package" to "com.android.settings")),
            request
        )
    }

    @Test
    fun rejectsUnknownOrMalformedCalls() {
        assertNull(FunctionGemmaActionDecoder.decode(ToolCall("erase_everything", "{}")))
        assertNull(FunctionGemmaActionDecoder.decode(ToolCall("read_battery", "not-json")))
    }
}
