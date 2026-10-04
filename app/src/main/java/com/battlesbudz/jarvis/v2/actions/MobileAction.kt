package com.battlesbudz.jarvis.v2.actions

import kotlin.math.round

sealed interface MobileAction {
    data object ReadBattery : MobileAction
    data class OpenApp(
        val appName: String,
        val packageNameHint: String? = null
    ) : MobileAction
    data class SetVolume(val level: Int) : MobileAction
    data class MediaControl(val action: MediaControlAction) : MobileAction
    data class OpenWebsite(val url: String) : MobileAction
    data class OpenSettings(val screen: SettingsScreen) : MobileAction
    data class Navigate(val destination: String) : MobileAction
}

/** Verbs accepted by the media_control tool; skip maps to next/previous track. */
enum class MediaControlAction(val verb: String, val label: String) {
    PLAY("play", "play"),
    PAUSE("pause", "pause"),
    TOGGLE("toggle", "play/pause toggle"),
    NEXT("next", "skip to next track"),
    PREVIOUS("previous", "skip to previous track");

    companion object {
        fun fromVerb(verb: String): MediaControlAction? =
            entries.firstOrNull { it.verb == verb }
    }
}

/**
 * Android system settings screens. Intent actions are platform string literals
 * (not android.provider.Settings constants) so the validator stays JVM-testable.
 */
enum class SettingsScreen(val key: String, val label: String, val intentAction: String) {
    WIFI("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS"),
    BLUETOOTH("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
    DISPLAY("display", "Display", "android.settings.DISPLAY_SETTINGS"),
    SOUND("sound", "Sound", "android.settings.SOUND_SETTINGS"),
    APPS("apps", "Apps", "android.settings.APPLICATION_SETTINGS"),
    BATTERY("battery", "Battery", "android.settings.BATTERY_SAVER_SETTINGS"),
    LOCATION("location", "Location", "android.settings.LOCATION_SOURCE_SETTINGS"),
    STORAGE("storage", "Storage", "android.settings.INTERNAL_STORAGE_SETTINGS"),
    NETWORK("network", "Network", "android.settings.WIRELESS_SETTINGS"),
    GENERAL("general", "Settings", "android.settings.SETTINGS");

    companion object {
        fun fromKey(key: String): SettingsScreen? = entries.firstOrNull { it.key == key }
        fun keys(): String = entries.joinToString { it.key }
    }
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
        "media_control" -> MediaControlAction.fromVerb(request.arguments["action"]?.trim().orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.MediaControl(it)) }
            ?: ActionValidation.Rejected("Media action must be one of play, pause, toggle, next, previous.")
        "open_website" -> normalizeUrl(request.arguments["url"].orEmpty())
            ?.let { ActionValidation.Valid(MobileAction.OpenWebsite(it)) }
            ?: ActionValidation.Rejected("A valid http or https website URL is required.")
        "open_settings" -> SettingsScreen.fromKey(request.arguments["screen"]?.trim().orEmpty().lowercase())
            ?.let { ActionValidation.Valid(MobileAction.OpenSettings(it)) }
            ?: ActionValidation.Rejected("Settings screen must be one of: ${SettingsScreen.keys()}.")
        "navigate" -> {
            val destination = request.arguments["destination"]?.trim().orEmpty()
            if (destination.isNotBlank()) ActionValidation.Valid(MobileAction.Navigate(destination))
            else ActionValidation.Rejected("A destination is required.")
        }
        else -> ActionValidation.Rejected("Unsupported action: ${request.name}")
    }

    /** Normalize a user/model-supplied URL to an https URL, rejecting dangerous schemes. */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("data:") || lower.startsWith("intent:")
        ) return null
        val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed
        else "https://$trimmed"
        val host = withScheme.substringAfter("://").substringBefore("/")
        if ('.' !in host || host.startsWith(".") || host.endsWith(".")) return null
        return withScheme
    }

    private fun parseVolumeLevel(raw: String): Int? {
        val trimmed = raw.trim()
        val hasPercentSuffix = trimmed.endsWith("%")
        val value = trimmed.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        // Some model outputs scale a percentage by 100 (50% -> 5000).
        val percent = if (
            !hasPercentSuffix &&
            value >= 1000.0 &&
            value <= 10000.0 &&
            value % 100.0 == 0.0
        ) value / 100.0 else value
        return percent.takeIf { it in 0.0..100.0 }?.let { round(it).toInt() }
    }
}
