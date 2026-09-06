package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import java.util.Locale

data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String
)

sealed interface AppResolution {
    data class Found(val app: InstalledApp) : AppResolution
    data class Ambiguous(val requestedName: String, val matches: List<InstalledApp>) : AppResolution
    data class NotFound(val requestedName: String) : AppResolution
}

/**
 * Resolves human app names locally. The model does not need to know Android
 * package IDs, and fuzzy matching is accepted only when it is unambiguous.
 */
class InstalledAppResolver(private val context: Context) {
    private val packageManager = context.packageManager

    fun resolve(requestedName: String, packageNameHint: String? = null): AppResolution {
        val requested = requestedName.trim()
        if (requested.isBlank()) return AppResolution.NotFound(requestedName)
        val apps = launcherApps()
        val normalized = normalize(requested)
        val requestedAliases = aliases[normalized].orEmpty()

        packageNameHint?.trim()?.takeIf { it.isNotBlank() }?.let { hint ->
            val hinted = apps.filter { it.packageName == hint }
                .filter { matchesName(it, normalized, requestedAliases) }
            if (hinted.size == 1) return AppResolution.Found(hinted.single())
            if (apps.any { it.packageName == hint }) {
                return AppResolution.NotFound(requestedName)
            }
        }

        val literal = apps.filter {
            normalize(it.label) == normalized ||
                normalize(it.packageName) == normalized ||
                normalize(it.packageName.substringAfterLast('.')) == normalized
        }
        if (literal.size == 1) return AppResolution.Found(literal.single())
        val exact = apps.filter { matchesName(it, normalized, requestedAliases) }
        if (exact.size == 1) return AppResolution.Found(exact.single())

        val aliasMatches = if (requestedAliases.isEmpty()) emptyList() else apps.filter {
            requestedAliases.contains(normalize(it.label)) ||
                requestedAliases.contains(normalize(it.packageName.substringAfterLast('.')))
        }
        if (aliasMatches.size == 1) return AppResolution.Found(aliasMatches.single())

        val candidates = (exact + aliasMatches)
            .distinctBy { "${it.packageName}/${it.activityName}" }
        if (candidates.size > 1) return AppResolution.Ambiguous(requestedName, candidates)

        val fuzzy = apps.filter {
            normalize(it.label).contains(normalized) ||
                normalized.contains(normalize(it.label))
        }.distinctBy { "${it.packageName}/${it.activityName}" }
        return when {
            fuzzy.size == 1 -> AppResolution.Found(fuzzy.single())
            fuzzy.size > 1 -> AppResolution.Ambiguous(requestedName, fuzzy.take(5))
            else -> AppResolution.NotFound(requestedName)
        }
    }

    fun launcherApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.toInstalledApp() }
            .distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedBy { it.label.lowercase(Locale.US) }
    }

    private fun ResolveInfo.toInstalledApp(): InstalledApp? {
        val info = activityInfo ?: return null
        val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
        if (label.isBlank() || info.packageName.isBlank()) return null
        return InstalledApp(label, info.packageName, info.name)
    }

    private fun matchesName(
        app: InstalledApp,
        normalized: String,
        requestedAliases: Set<String>
    ): Boolean =
        normalize(app.label) == normalized ||
            normalize(app.packageName) == normalized ||
            normalize(app.packageName.substringAfterLast('.')) == normalized ||
            requestedAliases.contains(normalize(app.label)) ||
            requestedAliases.contains(normalize(app.packageName.substringAfterLast('.')))

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private companion object {
        val aliases = mapOf(
            "facebook" to setOf("facebook", "facebooklite"),
            "youtube" to setOf("youtube", "youtubemusic"),
            "settings" to setOf("settings", "androidsettings"),
            "messenger" to setOf("messenger", "facebookmessenger")
        )
    }
}
