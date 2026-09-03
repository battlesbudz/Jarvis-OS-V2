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
        "set_volume" -> request.arguments["level"]?.toIntOrNull()
            ?.takeIf { it in 0..100 }
            ?.let { ActionValidation.Valid(MobileAction.SetVolume(it)) }
            ?: ActionValidation.Rejected("Volume must be an integer from 0 to 100.")
        else -> ActionValidation.Rejected("Unsupported action: ${request.name}")
    }
}
