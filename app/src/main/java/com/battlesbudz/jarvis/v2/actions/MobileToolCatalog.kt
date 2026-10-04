package com.battlesbudz.jarvis.v2.actions

import org.json.JSONObject

/**
 * The single source of truth for model-visible native actions. The same entries
 * generate LiteRT declarations and validate the strict side-effect boundary.
 */
object MobileToolCatalog {
    const val VERSION = 1

    enum class ParameterType(val schemaType: String) { STRING("string"), INTEGER("integer") }

    data class Parameter(
        val name: String,
        val type: ParameterType,
        val description: String,
        val minimum: Int? = null,
        val maximum: Int? = null,
        val minLength: Int? = null,
        val pattern: String? = null
    )

    data class Tool(
        val name: String,
        val version: Int = VERSION,
        val description: String,
        val parameters: List<Parameter> = emptyList()
    ) {
        fun schemaJson(): String = buildString {
            append("{\"name\":").append(JSONObject.quote(name))
            append(",\"description\":").append(JSONObject.quote(description))
            append(",\"parameters\":{\"type\":\"object\",\"properties\":{")
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) append(',')
                append(JSONObject.quote(parameter.name)).append(":{\"type\":")
                    .append(JSONObject.quote(parameter.type.schemaType))
                    .append(",\"description\":").append(JSONObject.quote(parameter.description))
                parameter.minimum?.let { append(",\"minimum\":").append(it) }
                parameter.maximum?.let { append(",\"maximum\":").append(it) }
                parameter.minLength?.let { append(",\"minLength\":").append(it) }
                parameter.pattern?.let { append(",\"pattern\":").append(JSONObject.quote(it)) }
                append('}')
            }
            append("},\"required\":[")
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) append(',')
                append(JSONObject.quote(parameter.name))
            }
            append("],\"additionalProperties\":false}}")
        }
    }

    private val entries = listOf(
        Tool(
            name = "read_battery",
            description = "Read the phone battery percentage, charge level, and current battery status. Use this when the user asks how much battery the phone has or what the battery percentage is."
        ),
        Tool(
            name = "open_app",
            description = "Open an installed Android application.",
            parameters = listOf(Parameter(
                name = "app",
                type = ParameterType.STRING,
                description = "The installed app's human-readable name, such as Facebook or YouTube.",
                minLength = 1,
                pattern = ".*\\S.*"
            ))
        ),
        Tool(
            name = "set_volume",
            description = "Set the phone media volume percentage from 0 to 100.",
            parameters = listOf(Parameter(
                name = "level",
                type = ParameterType.INTEGER,
                description = "The desired media volume percentage.",
                minimum = 0,
                maximum = 100
            ))
        ),
        Tool(
            name = "media_control",
            description = "Control media playback on the phone: play, pause, toggle play/pause, or skip to the next or previous track.",
            parameters = listOf(Parameter(
                name = "action",
                type = ParameterType.STRING,
                description = "The media command: one of play, pause, toggle, next, previous.",
                minLength = 1,
                pattern = "^(play|pause|toggle|next|previous)$"
            ))
        ),
        Tool(
            name = "open_website",
            description = "Open a website URL in the phone browser.",
            parameters = listOf(Parameter(
                name = "url",
                type = ParameterType.STRING,
                description = "The website URL, e.g. https://example.com or example.com.",
                minLength = 1
            ))
        ),
        Tool(
            name = "open_settings",
            description = "Open an Android system settings screen.",
            parameters = listOf(Parameter(
                name = "screen",
                type = ParameterType.STRING,
                description = "The settings screen: one of wifi, bluetooth, display, sound, apps, battery, location, storage, network, general.",
                minLength = 1,
                pattern = "^(wifi|bluetooth|display|sound|apps|battery|location|storage|network|general)$"
            ))
        ),
        Tool(
            name = "navigate",
            description = "Show map directions to a destination address or place name.",
            parameters = listOf(Parameter(
                name = "destination",
                type = ParameterType.STRING,
                description = "The destination address or place name.",
                minLength = 1
            ))
        ),
        Tool(
            name = "screen_observe",
            description = "Look at the current phone screen and return a compact list of the visible interactive elements with IDs and an observation token. Call this before screen_tap, screen_scroll, or screen_type; element IDs and tokens expire when the screen changes."
        ),
        Tool(
            name = "screen_tap",
            description = "Tap a screen element from the latest screen_observe result. The target ID and token must come from that observation; stale or mismatched targets are rejected and never tapped.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "screen_scroll",
            description = "Scroll a scrollable element from the latest screen_observe result up or down. The target ID and token must come from that observation; stale targets are rejected.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "direction",
                    type = ParameterType.STRING,
                    description = "The scroll direction: up or down.",
                    minLength = 1,
                    pattern = "^(up|down)$"
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        ),
        Tool(
            name = "screen_type",
            description = "Type text into an editable field from the latest screen_observe result. The target ID and token must come from that observation; stale targets are rejected.",
            parameters = listOf(
                Parameter(
                    name = "target",
                    type = ParameterType.STRING,
                    description = "The element ID from screen_observe, e.g. n3.",
                    minLength = 1,
                    pattern = "^n[0-9]{1,4}$"
                ),
                Parameter(
                    name = "text",
                    type = ParameterType.STRING,
                    description = "The text to type, 1 to 200 characters.",
                    minLength = 1
                ),
                Parameter(
                    name = "token",
                    type = ParameterType.STRING,
                    description = "The observation token from screen_observe.",
                    minLength = 1,
                    pattern = "^[0-9a-f]{16}$"
                )
            )
        )
    )

    fun all(): List<Tool> = entries
    fun find(name: String): Tool? = entries.firstOrNull { it.name == name }

    /** Strict decoder contract: exact keys and JSON types only, with no coercion. */
    fun decodeStrict(name: String, arguments: JSONObject): ActionRequest? {
        val tool = find(name) ?: return null
        if (arguments.length() != tool.parameters.size || tool.parameters.any { !arguments.has(it.name) }) return null
        val decoded = buildMap {
            for (parameter in tool.parameters) {
                val value = arguments.opt(parameter.name)
                val canonical = when (parameter.type) {
                    ParameterType.STRING -> (value as? String)?.takeIf { string ->
                        (parameter.minLength == null || string.length >= parameter.minLength) &&
                            (parameter.pattern == null || Regex(parameter.pattern).matches(string))
                    }?.trim()
                    ParameterType.INTEGER -> (value as? Int)?.takeIf { integer ->
                        (parameter.minimum == null || integer >= parameter.minimum) &&
                            (parameter.maximum == null || integer <= parameter.maximum)
                    }?.toString()
                } ?: return null
                put(parameter.name, canonical)
            }
        }
        return ActionRequest(name, decoded)
    }
}
