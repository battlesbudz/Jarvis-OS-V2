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
