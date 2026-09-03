package com.battlesbudz.jarvis.v2.actions

sealed interface MobileAction {
    data object ReadBattery : MobileAction
    data class OpenApp(
        val appName: String,
        val packageNameHint: String? = null
    ) : MobileAction
    data class SetVolume(val level: Int) : MobileAction
}

data class ActionRequest(
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

sealed interface ActionValidation {
    data class Valid(val action: MobileAction) : ActionValidation
    data class Rejected(val reason: String) : ActionValidation
}

class MobileActionValidator {
    private val packageNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    fun validate(request: ActionRequest): ActionValidation = when (request.name) {
        "read_battery" -> ActionValidation.Valid(MobileAction.ReadBattery)
        "open_app" -> {
            val appName = request.arguments["app"]?.trim().orEmpty()
            val packageHint = request.arguments["package"]?.trim().orEmpty().takeIf {
                it.matches(packageNamePattern)
            }
            when {
                appName.isBlank() && packageHint == null ->
                    ActionValidation.Rejected("An installed app name is required.")
                else -> ActionValidation.Valid(
                    MobileAction.OpenApp(
                        appName = appName.ifBlank { packageHint!! },
                        packageNameHint = packageHint
                    )
                )
            }
        }
        "set_volume" -> parseVolumeLevel(request.arguments["level"].orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.SetVolume(it)) }
            ?: ActionValidation.Rejected("Volume must be a percentage from 0 to 100.")
        else -> ActionValidation.Rejected("Unsupported action: ${request.name}")
    }

    private fun parseVolumeLevel(raw: String): Int? {
        val value = raw.trim().removeSuffix("%").trim().toDoubleOrNull() ?: return null
        // Some FunctionGemma outputs scale a percentage by 100 (50% -> 5000).
        val percent = if (value > 100.0 && value <= 10000.0) value / 100.0 else value
        return percent.takeIf { it in 0.0..100.0 }?.let { round(it).toInt() }
    }
}
