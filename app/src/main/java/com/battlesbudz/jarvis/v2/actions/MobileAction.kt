package com.battlesbudz.jarvis.v2.actions

sealed interface MobileAction {
    data object ReadBattery : MobileAction
    data class OpenApp(val packageName: String) : MobileAction
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
        "open_app" -> request.arguments["package"]
            ?.takeIf { it.matches(packageNamePattern) }
            ?.let { ActionValidation.Valid(MobileAction.OpenApp(it)) }
            ?: ActionValidation.Rejected("A valid package name is required.")
        "set_volume" -> request.arguments["level"]?.toIntOrNull()
            ?.takeIf { it in 0..100 }
            ?.let { ActionValidation.Valid(MobileAction.SetVolume(it)) }
            ?: ActionValidation.Rejected("Volume must be an integer from 0 to 100.")
        else -> ActionValidation.Rejected("Unsupported action: ${request.name}")
    }
}