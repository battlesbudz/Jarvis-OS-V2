package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import org.json.JSONArray

class DiagnosticRecorder(
    private val preferences: SharedPreferences
) {
    private val entries = mutableListOf<String>()

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
            entries.clear()
            entries.addAll(restored)
        }
        return restored
    }

    fun snapshot(): String {
        val current = synchronized(entries) { entries.takeLast(20) }
        return current.joinToString("\n\n")
            .ifBlank { "No prior runtime diagnostics." }
    }

    fun record(entry: String) {
        synchronized(entries) {
            entries.add(entry.take(6_000))
            while (entries.size > 20) entries.removeAt(0)
            val persisted = JSONArray().also { array ->
                entries.takeLast(20).forEach(array::put)
            }.toString()
            preferences.edit().putString("diagnostics", persisted).apply()
        }
    }
}
