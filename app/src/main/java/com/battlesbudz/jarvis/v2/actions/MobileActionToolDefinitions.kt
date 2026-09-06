package com.battlesbudz.jarvis.v2.actions

import com.google.ai.edge.litertlm.OpenApiTool

/**
 * The tool declarations supplied to FunctionGemma. The model must select from
 * these names; Kotlin remains responsible for validation and execution.
 */
object MobileActionToolDefinitions {
    fun all(): List<OpenApiTool> = listOf(
        ReadBatteryTool(),
        OpenAppTool(),
        SetVolumeTool()
    )

    private class ReadBatteryTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = """
            {
              "name": "read_battery",
              "description": "Read the phone battery percentage, charge level, and current battery status. Use this when the user asks how much battery the phone has or what the battery percentage is.",
              "parameters": {
                "type": "object",
                "properties": {},
                "required": []
              }
            }
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = "{}"
    }

    private class OpenAppTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = """
            {
              "name": "open_app",
              "description": "Open an installed Android application.",
              "parameters": {
                "type": "object",
                "properties": {
                  "app": {
                    "type": "string",
                    "description": "The installed app's human-readable name, such as Facebook or YouTube."
                  },
                  "package": {
                    "type": "string",
                    "description": "Optional exact Android package name when already known."
                  }
                },
                "required": ["app"]
              }
            }
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = "{}"
    }

    private class SetVolumeTool : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = """
            {
              "name": "set_volume",
              "description": "Set the phone media volume percentage from 0 to 100.",
              "parameters": {
                "type": "object",
                "properties": {
                  "level": {
                    "type": "integer",
                    "description": "The desired media volume percentage."
                  }
                },
                "required": ["level"]
              }
            }
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = "{}"
    }
}
