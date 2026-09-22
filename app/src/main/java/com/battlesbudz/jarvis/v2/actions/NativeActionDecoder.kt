package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject

/**
 * Converts Gemma's structured tool call into the small, typed
 * action contract used by the Android executor.
 */
object NativeActionDecoder {
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
                    listOf("app", "app_name").firstNotNullOfOrNull { key ->
                        args.optString(key).takeIf { it.isNotBlank() }
                    }?.let { put("app", it) }
                    listOf("package", "package_name").firstNotNullOfOrNull { key ->
                        args.optString(key).takeIf { it.isNotBlank() }
                    }?.let { put("package", it) }
                }
            )
            "set_volume" -> ActionRequest(
                name = call.name,
                arguments = mapOf("level" to args.optString("level"))
            )
            else -> null
        }
    }
    /** Strict boundary used before real device side effects; legacy decode stays tolerant for old fixtures. */
    fun decodeStrict(call: ToolCall): ActionRequest? {
        val json = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return null
        val args = when {
            json.has("args") && json.opt("args") is JSONObject && json.length() == 1 -> json.getJSONObject("args")
            else -> json
        }
        fun exact(vararg keys: String) = args.length() == keys.size && keys.all(args::has)
        return when (call.name) {
            "read_battery" -> if (exact()) ActionRequest(call.name) else null
            "open_app" -> if (exact("app")) args.optString("app").trim().takeIf { it.isNotBlank() }?.let {
                ActionRequest(call.name, mapOf("app" to it)) } else null
            "set_volume" -> if (exact("level")) {
                val raw = args.opt("level")?.toString()?.trim().orEmpty()
                raw.takeIf { it.matches(Regex("(?:0|[1-9][0-9]?)|100")) }?.let { ActionRequest(call.name, mapOf("level" to it)) }
            } else null
            else -> null
        }
    }

}
