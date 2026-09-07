package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import org.json.JSONArray

class DiagnosticRecorder(
    private val preferences: SharedPreferences
) {
    private val entries = mutableListOf<String>()
    private var sessionLabel = "Previous app runtime (may include earlier calls or chat)"

    fun startSession(label: String) {
        synchronized(entries) {
            sessionLabel = "$label startedAtMs=${System.currentTimeMillis()}"
            entries.clear()
            preferences.edit().putString("diagnostics_session", sessionLabel)
                .putString("diagnostics", "[]").apply()
        }
    }

    fun restore(): List<String> {
        val stored = preferences.getString("diagnostics", null).orEmpty()
        val restored = if (stored.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(stored)
                (0 until array.length())
                    .map { array.getString(it) }
                    .filter { it.isNotBlank() }
                    .takeLast(20)
            }.getOrElse {
                stored.split("\n\n").filter { it.isNotBlank() }.takeLast(20)
            }
        }
        synchronized(entries) {
            sessionLabel = preferences.getString("diagnostics_session", null) ?: sessionLabel
            entries.clear()
            entries.addAll(restored)
        }
        return restored
    }

    fun snapshot(): String {
        return synchronized(entries) {
            preferences.getString("previous_process_exit", "").orEmpty() + "\n\n" +
                "$sessionLabel\n\n" + entries.takeLast(20).joinToString("\n\n")
                .ifBlank { "No runtime events in this session yet." }
        }
    }

    fun recordPreviousProcessExit(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.pid != android.os.Process.myPid() } ?: return
            if (exit.timestamp <= preferences.getLong("previous_process_exit_at", 0)) return
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                android.app.ApplicationExitInfo.REASON_CRASH -> "managed_crash"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                android.app.ApplicationExitInfo.REASON_ANR -> "not_responding"
                else -> "reason_${exit.reason}"
            }
            preferences.edit().putLong("previous_process_exit_at", exit.timestamp)
                .putString("previous_process_exit", "Previous Android process exit (not the current call): " +
                    "atMs=${exit.timestamp} reason=$reason status=${exit.status} " +
                    "pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description?.take(1000)}")
                .apply()
        }
    }

    fun record(entry: String) {
        synchronized(entries) {
            entries.add("atMs=${System.currentTimeMillis()}\n${entry.take(6_000)}")
            while (entries.size > 20) entries.removeAt(0)
            val persisted = JSONArray().also { array ->
                entries.takeLast(20).forEach(array::put)
            }.toString()
            preferences.edit().putString("diagnostics", persisted).apply()
        }
    }
}
