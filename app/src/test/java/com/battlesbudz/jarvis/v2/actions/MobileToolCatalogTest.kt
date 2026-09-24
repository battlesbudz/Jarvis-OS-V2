package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileToolCatalogTest {
    @Test fun catalogDrivesEveryLiteRtSchema() {
        val entries = MobileToolCatalog.all()
        val schemas = MobileActionToolDefinitions.all().map { JSONObject(it.getToolDescriptionJsonString()) }
        assertEquals(entries.map { it.name }, schemas.map { it.getString("name") })
        entries.zip(schemas).forEach { (entry, schema) ->
            val parameters = schema.getJSONObject("parameters")
            assertEquals("object", parameters.getString("type"))
            assertFalse(parameters.getBoolean("additionalProperties"))
            assertEquals(entry.parameters.map { it.name },
                (0 until parameters.getJSONArray("required").length()).map { parameters.getJSONArray("required").getString(it) })
            entry.parameters.forEach { parameter ->
                val property = parameters.getJSONObject("properties").getJSONObject(parameter.name)
                assertEquals(parameter.type.schemaType, property.getString("type"))
                parameter.minimum?.let { assertEquals(it, property.getInt("minimum")) }
                parameter.maximum?.let { assertEquals(it, property.getInt("maximum")) }
                parameter.minLength?.let { assertEquals(it, property.getInt("minLength")) }
                parameter.pattern?.let { assertEquals(it, property.getString("pattern")) }
            }
        }
        assertEquals(listOf("read_battery", "open_app", "set_volume"), entries.map { it.name })
        assertTrue(entries.all { it.version == MobileToolCatalog.VERSION })
    }

    @Test fun strictDecoderUsesCatalogExactTypesAndArguments() {
        fun strict(name: String, arguments: String) = NativeActionDecoder.decodeStrict(ToolCall(name, arguments))

        assertEquals(ActionRequest("open_app", mapOf("app" to "Settings")), strict("open_app", "{\"app\":\" Settings \"}"))
        assertEquals(ActionRequest("set_volume", mapOf("level" to "0")), strict("set_volume", "{\"args\":{\"level\":0}}"))
        listOf(
            "{\"app\":4}", "{\"app\":null}", "{\"app\":{}}", "{\"app\":\"   \"}", "{\"app\":\"Settings\",\"package\":\"com.android.settings\"}"
        ).forEach { assertNull(strict("open_app", it)) }
        listOf("{\"level\":\"20\"}", "{\"level\":20.0}", "{\"level\":-1}", "{\"level\":101}", "{\"level\":null}")
            .forEach { assertNull(strict("set_volume", it)) }
        assertNull(strict("read_battery", "{\"unused\":true}"))
    }

    @Test fun legacyDecodeRemainsTolerantAndSdkCallbackCannotClaimSuccess() {
        assertEquals(ActionRequest("open_app", mapOf("app" to "4")),
            NativeActionDecoder.decode(ToolCall("open_app", "{\"app\":4}")))
        assertEquals(ActionRequest("set_volume", mapOf("level" to "20")),
            NativeActionDecoder.decode(ToolCall("set_volume", "{\"level\":20}")))
        MobileActionToolDefinitions.all().forEach { tool ->
            assertTrue(JSONObject(tool.execute("{}")).has("error"))
        }
    }
}
