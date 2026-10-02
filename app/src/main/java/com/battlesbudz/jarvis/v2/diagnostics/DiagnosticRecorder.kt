package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import org.json.JSONArray

class DiagnosticRecorder(
    private val preferences: SharedPreferences,
    private val buildLabel: String = "unknown",
    private val clock: () -> Long = System::currentTimeMillis,
    private val sourceTimestamp: () -> Long = clock
) {
    private val inferencePrompts = ArrayDeque<String>()
    private val entries = mutableListOf<String>()
    private val important = mutableListOf<String>()
    private val summaries = mutableListOf<String>()
    private val turnEvidence = linkedMapOf<String, String>()

    /** Separate retention: timing chatter cannot evict or truncate the model input. */
    fun recordInferencePrompt(entry: String) = synchronized(entries) {
        scrub()
        inferencePrompts.addLast(wrap(entry, completeSources = listOf(entry)))
        while (inferencePrompts.size > 24) inferencePrompts.removeFirst()
        preferences.edit().putString("diagnostics_inference_prompts",
            JSONArray().also { array -> inferencePrompts.forEach(array::put) }.toString()).apply()
    }

    data class FullSource(val text: String, val capturedAtMs: Long)
    /** Called only with complete finalized source fields, never display excerpts. */
    fun recordSourceInferencePrompt(submittedText: String, sources: List<FullSource>, metadata: String = "") = synchronized(entries) {
        scrub()
        val entry = metadata + "\n--- Exact submitted text begins ---\n" + submittedText + "\n--- Exact submitted text ends ---"
        val captured = sources.minOfOrNull { minOf(it.capturedAtMs, sourceTimestamp()) } ?: 0
        inferencePrompts.addLast(wrap(entry, originalCapture = captured, completeSources = sources.map { it.text }))
        while (inferencePrompts.size > 24) inferencePrompts.removeFirst()
        preferences.edit().putString("diagnostics_inference_prompts", JSONArray().also { a -> inferencePrompts.forEach(a::put) }.toString()).apply()
    }

    fun recordTurnEvidence(turn: String, category: String, entry: String) = synchronized(entries) {
        scrub()
        val safeKey = com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(turn) + "/" + com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(category)
        turnEvidence[safeKey] = if (safeNumericInference(entry)) wrap(entry, 1500, completeSources = listOf(entry))
            else wrap("turn=$turn $category\n$entry", 1500)
        while (turnEvidence.keys.map { it.substringBefore('/') }.distinct().size > 12) {
            val oldest = turnEvidence.keys.first().substringBefore('/')
            turnEvidence.keys.removeAll { it.substringBefore('/') == oldest }
        }
        val saved = org.json.JSONObject().also { obj -> turnEvidence.forEach { (key, value) -> obj.put(key, value) } }
        preferences.edit().putString("diagnostics_turn_evidence", saved.toString()).apply()
    }
    private var sessionLabel = "Previous app runtime (may include earlier calls or chat)"

    fun startSession(label: String) {
        synchronized(entries) {
            sessionLabel = wrap("Runtime diagnostic session recordedByBuild=$buildLabel", completeSources = listOf(buildLabel))
            inferencePrompts.clear()
            entries.clear()
            important.clear()
            summaries.clear()
            turnEvidence.clear()
            preferences.edit().remove("diagnostics_inference_prompts").remove("diagnostics_turn_evidence").remove("diagnostics_summaries").remove("diagnostics_important").putString("diagnostics_session", sessionLabel)
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
                    .takeLast(100)
            }.getOrElse {
                stored.split("\n\n").filter { it.isNotBlank() }.takeLast(100)
            }
        }
        synchronized(entries) {
            inferencePrompts.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_inference_prompts", "[]"))
                ((saved.length() - 24).coerceAtLeast(0) until saved.length()).forEach { inferencePrompts.addLast(saved.getString(it)) }
            }
            sessionLabel = preferences.getString("diagnostics_session", null) ?: sessionLabel
            entries.clear()
            entries.addAll(restored)
            important.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_important", "[]"))
                (0 until saved.length()).map { readSafe(saved.getString(it)).take(1200) }.takeLast(64)
            }.getOrDefault(emptyList()).forEach(important::add)
        }
        synchronized(entries) {
            summaries.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_summaries", "[]"))
                (0 until saved.length()).map { readSafe(saved.getString(it)).take(1200) }.takeLast(48)
            }.getOrDefault(emptyList()).forEach(summaries::add)
        }
        synchronized(entries) {
            turnEvidence.clear()
            runCatching {
                val saved = org.json.JSONObject(preferences.getString("diagnostics_turn_evidence", "{}"))
                saved.keys().asSequence().toList().takeLast(60).forEach { key -> turnEvidence[key] = readSafe(saved.getString(key)).take(1700) }
            }
        }
        synchronized(entries) { scrub() }
        return synchronized(entries) { entries.toList() }
    }

    fun snapshot(): String {
        return synchronized(entries) {
            scrub()
            "Running build: ${if (Privacy.excluded(buildLabel)) Privacy.EXCLUDED else buildLabel}\n$sessionLabel\n\nExact Gemma prompt submissions (latest 24; includes drafts and retries):\n${inferencePrompts.joinToString("\n\n")}\n\nRetained turn evidence (up to 12 turns):\n${turnEvidence.values.joinToString("\n\n")}\n\nTiming and recognition summaries:\n${summaries.joinToString("\n\n")}\n\nCall actions and turns:\n${important.joinToString("\n\n")}\n\nRecent audio events:\n" + entries.takeLast(100).joinToString("\n\n")
                .ifBlank { "No runtime events in this session yet." }
        }
    }

    private fun wrap(entry: String, limit: Int = Privacy.MAX_TEXT_CHARS, originalCapture: Long = sourceTimestamp(), completeSources: List<String>? = null): String {
        // Fingerprint-only provenance also prevents an identical late retry from renewing expiry.
        val key = com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(entry)
        val times = runCatching { org.json.JSONObject(preferences.getString("diagnostics_source_times", "{}")) }.getOrDefault(org.json.JSONObject())
        val previous = if (times.has(key)) times.optLong(key, 0) else null
        val captured = Privacy.originalTimestamp(originalCapture, previous)
        if (times.length() >= 20_000 && !times.has(key)) return "atMs=0\n${Privacy.EXPIRED}"
        times.put(key, captured)
        preferences.edit().putString("diagnostics_source_times", times.toString()).apply()
        val sourceDecision = if (completeSources != null) Privacy.placeholder(completeSources + entry, captured, clock()) else null
        val unproven = completeSources == null && !safeNumericInference(entry)
        val placeholder = sourceDecision ?: if (unproven) Privacy.EXCLUDED else Privacy.placeholder(listOf(entry), captured, clock())
        val payload = placeholder ?: entry.take(limit)
        if (placeholder == null) {
            val bindings = fullSourceBindings()
            val payloadKey = com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(payload)
            if (!bindings.has(payloadKey) && bindings.length() >= 20_000) return "atMs=$captured\n${Privacy.EXCLUDED}"
            bindings.put(payloadKey, captured)
            preferences.edit().putString("diagnostics_full_source_bindings", bindings.toString()).apply()
        }
        return "atMs=$captured\n$payload"
    }
    private fun fullSourceBindings() = runCatching {
        org.json.JSONObject(preferences.getString("diagnostics_full_source_bindings", "{}"))
    }.getOrDefault(org.json.JSONObject())
    /** The legacy inference benchmark writer has a fixed schema and no source-text field. */
    private fun safeNumericInference(entry: String): Boolean {
        val lines = entry.lines()
        if (lines.size < 3 || lines[0] != "Inference") return false
        val stages = setOf("answer", "tool response", "invalid tool retry", "factuality check", "reference fallback", "reference retry", "repetition repair")
        if (lines[1].removePrefix("stage=") !in stages || !lines[1].startsWith("stage=")) return false
        val expected = setOf("promptChars", "timeToFirstTokenMs", "nativeSubmitMs", "firstCallbackMs", "totalGenerationTimeMs", "outputTokensEstimated", "streamEvents", "decodeTokensPerSecondEstimated")
        val fields = lines.drop(2).joinToString(" ").split(Regex("\\s+"))
        if (fields.size != expected.size) return false
        val seen = mutableSetOf<String>()
        return fields.all { field ->
            val parts = field.split('=')
            val number = parts.getOrNull(1)?.toDoubleOrNull()
            val boundedNumber = number != null && number.isFinite() && number in -1.0..1_000_000_000.0
            val unknownOptionalTiming = parts[0] in setOf("nativeSubmitMs", "firstCallbackMs") && parts.getOrNull(1) == "null"
            parts.size == 2 && parts[0] in expected && seen.add(parts[0]) && (boundedNumber || unknownOptionalTiming)
        } && seen == expected
    }

    private fun readSafe(value: String): String {
        val firstLine = value.substringBefore('\n')
        // Only the recorder's header has timestamp provenance. Scan every source character,
        // but never feed trusted epoch metadata to the bare-card-number detector.
        val header = Regex("^atMs=(\\d+)$").matchEntire(firstLine)
        val legacyHeader = if (header == null) Regex("^turn=.* atMs=(\\d+)$").matchEntire(firstLine) else null
        val captured = (header ?: legacyHeader)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val payload = if (header != null) value.substringAfter('\n', "") else if (legacyHeader != null)
            firstLine.substringBeforeLast(" atMs=") + "\n" + value.substringAfter('\n', "") else value
        val bound = fullSourceBindings().optLong(com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(payload), 0) == captured && captured > 0
        val contentFree = payload == Privacy.EXCLUDED || payload == Privacy.EXPIRED
        val decision = if (!bound && !contentFree) Privacy.EXCLUDED else Privacy.placeholder(listOf(payload), captured, clock())
        return decision?.let { "atMs=$captured\n$it" } ?: value
    }
    private fun scrub() {
        fun clean(values: MutableList<String>) { values.indices.forEach { values[it] = readSafe(values[it]) } }
        val prompts = inferencePrompts.map(::readSafe)
        inferencePrompts.clear(); prompts.forEach(inferencePrompts::addLast)
        clean(entries); clean(important); clean(summaries)
        turnEvidence.keys.toList().forEach { key ->
            val value = turnEvidence.remove(key) ?: return@forEach
            val safeKey = if (Regex("[0-9a-f]{32}/[0-9a-f]{32}").matches(key)) key else
                com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(key.substringBefore('/')) + "/" +
                    com.battlesbudz.jarvis.v2.memory.MemoryPolicy.sourceKey(key.substringAfter('/', ""))
            turnEvidence[safeKey] = readSafe(value)
        }
        // Legacy session labels have no trustworthy original capture time.
        sessionLabel = readSafe(sessionLabel)
        val edit = preferences.edit()
        listOf("diagnostics" to entries, "diagnostics_important" to important, "diagnostics_summaries" to summaries,
            "diagnostics_inference_prompts" to inferencePrompts.toList()).forEach { (key, values) ->
            edit.putString(key, JSONArray().also { a -> values.forEach(a::put) }.toString())
        }
        edit.putString("diagnostics_turn_evidence", org.json.JSONObject().also { obj -> turnEvidence.forEach { (key, value) -> obj.put(key, value) } }.toString())
        edit.putString("diagnostics_session", sessionLabel)
        preferences.getString("previous_process_exit", null)?.let { edit.putString("previous_process_exit", readSafe(it)) }
        edit.apply()
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
                .putString("previous_process_exit", wrap("Previous Android process exit (not the current call): " +
                    "reason=$reason status=${exit.status} " +
                    "pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description.orEmpty()}", 1200, exit.timestamp, completeSources = listOf(exit.description.orEmpty())))
                .apply()
        }
    }

    fun recordSummary(entry: String) {
        synchronized(entries) {
            scrub()
            summaries.add(wrap(entry, 1200))
            while (summaries.size > 48) summaries.removeAt(0)
            val saved = JSONArray().also { array -> summaries.forEach(array::put) }
            preferences.edit().putString("diagnostics_summaries", saved.toString()).apply()
        }
    }

    fun recordImportant(entry: String) {
        synchronized(entries) {
            scrub()
            important.add(wrap(entry, 1200))
            while (important.size > 64) important.removeAt(0)
            val saved = JSONArray().also { array -> important.forEach(array::put) }
            preferences.edit().putString("diagnostics_important", saved.toString()).apply()
        }
    }

    fun record(entry: String) {
        synchronized(entries) {
            scrub()
            entries.add(wrap(entry, 1200))
            while (entries.size > 100) entries.removeAt(0)
            val persisted = JSONArray().also { array ->
                entries.takeLast(100).forEach(array::put)
            }.toString()
            preferences.edit().putString("diagnostics", persisted).apply()
        }
    }
}
