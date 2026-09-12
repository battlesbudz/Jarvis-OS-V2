package com.battlesbudz.jarvis.v2.ai

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Actual phone measurements. Benchmark output is never added to chat history. */
class GemmaBenchmarkStore(private val preferences: SharedPreferences) {
    @Synchronized fun records(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString("results", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.takeLast(48)
    }.getOrDefault(emptyList())

    @Synchronized fun add(record: JSONObject) {
        val array = JSONArray()
        (records() + record).takeLast(48).forEach { array.put(it) }
        preferences.edit().putString("results", array.toString()).apply()
    }

    @Synchronized fun snapshot() = records().joinToString("\n\n") { it.toString(2) }
        .ifBlank { "No Gemma acceleration measurements yet." }
}
