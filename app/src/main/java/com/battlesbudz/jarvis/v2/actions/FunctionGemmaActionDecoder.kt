package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject

/**
 * Converts LiteRT-LM's structured FunctionGemma call into the small, typed
 * action contract used by the Android executor.
 */
object FunctionGemmaActionDecoder {
    fun decode(call: ToolCall): ActionRequest? {
        val json = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return null
        val args = when {
            json.has("args") && json.opt("args") is JSONObject -> json.getJSONObject("args")
            json.has("args") && json.optString("args").startsWith("{") ->
                runCatching { JSONObject(json.optString("args")) }.getOrNull() ?: return null
            else -> json
        }

        return when (call.name) {
            "read_battery" -> ActionRequest(call.name)
            "open_app" -> ActionRequest(
                name = call.name,
                arguments = buildMap {
                    args.optString("app").takeIf { it.isNotBlank() }?.let { put("app", it) }
                    args.optString("package").takeIf { it.isNotBlank() }?.let { put("package", it) }
                }
            )
            "set_volume" -> ActionRequest(
                name = call.name,
                arguments = mapOf("level" to args.optString("level"))
            )
            else -> null
        }
    }
}
