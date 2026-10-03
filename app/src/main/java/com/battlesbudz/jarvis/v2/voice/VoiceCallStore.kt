package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.memory.SourceTextPersistencePolicy as Privacy
import org.json.JSONArray
import org.json.JSONObject

interface VoiceCallStore {
    fun list(): List<VoiceCallRecord>
    fun save(call: VoiceCallRecord)
    /** Transient text may be coalesced; accepted turns and lifecycle boundaries use save(). */
    fun saveProgress(call: VoiceCallRecord) = save(call)
    fun delete(callId: String)
}
/** Local app-private storage. No export, upload, or raw-audio persistence. */
class SharedPreferencesVoiceCallStore(
    private val preferences: SharedPreferences,
    private val key: String = "voice_calls",
    private val clock: () -> Long = System::currentTimeMillis
) : VoiceCallStore {
    @Synchronized
    override fun list(): List<VoiceCallRecord> {
        val stored = preferences.getString(key, null).orEmpty()
        val safe = decode(stored).map { sanitize(it) }
        val encoded = encode(safe)
        if (encoded != stored) preferences.edit().putString(key, encoded).apply()
        return safe
    }

    @Synchronized
    override fun save(call: VoiceCallRecord) {
        val existing = list()
        val prior = existing.firstOrNull { it.id == call.id }
        val updated = existing.filterNot { it.id == call.id } + sanitize(call, prior)
        preferences.edit().putString(key, encode(updated)).apply()
    }

    @Synchronized
    override fun delete(callId: String) {
        preferences.edit().putString(key, encode(list().filterNot { it.id == callId })).apply()
    }

    private fun sanitize(call: VoiceCallRecord, prior: VoiceCallRecord? = null): VoiceCallRecord {
        val started = Privacy.originalTimestamp(call.startedAtMs, prior?.startedAtMs)
        val transcript = call.transcript.mapIndexed { index, entry ->
            val previous = prior?.transcript?.getOrNull(index)
            val captured = Privacy.originalTimestamp(entry.timestampMs, previous?.timestampMs)
            val payload = listOf(entry.text, entry.role) + entry.actions.flatMap { listOf(it.name, it.message) } +
                entry.delivery?.spans.orEmpty().map { it.text } + entry.latency?.let { latency ->
                    listOf(latency.voice.orEmpty()) + latency.passes.map { it.stage } }.orEmpty() + listOfNotNull(previous?.text?.takeIf { it == Privacy.EXCLUDED || it == Privacy.EXPIRED })
            val placeholder = Privacy.placeholder(payload, captured, clock())
            if (placeholder == null) entry.copy(timestampMs = captured) else entry.copy(text = placeholder,
                role = if (entry.role == "You") "You" else "Jarvis", timestampMs = captured,
                actions = entry.actions.map { it.copy(name = if (Privacy.excluded(it.name)) "action" else it.name, message = placeholder) },
                delivery = entry.delivery?.let { delivery -> delivery.copy(spans = delivery.spans.map { it.copy(text = placeholder) }) },
                latency = entry.latency?.let { latency -> latency.copy(voice = latency.voice?.let { placeholder },
                    passes = latency.passes.map { it.copy(stage = "inference") }) })
        }
        // Titles and task lists are summaries of this call, so use the original call clock.
        val derived = listOf(call.title.orEmpty()) + call.taskStatus?.let { it.completedSteps + it.pendingSteps }.orEmpty() +
            transcript.map { it.text }.filter { it == Privacy.EXCLUDED || it == Privacy.EXPIRED } +
            listOfNotNull(prior?.title?.takeIf { it == Privacy.EXCLUDED || it == Privacy.EXPIRED })
        val placeholder = Privacy.placeholder(derived, started, clock())
        return call.copy(startedAtMs = started, transcript = transcript,
            title = if (placeholder == null) call.title else placeholder,
            taskStatus = call.taskStatus?.let { task -> if (placeholder == null) task else task.copy(
                completedSteps = task.completedSteps.map { placeholder }, pendingSteps = task.pendingSteps.map { placeholder }) })
    }

    companion object {
        internal fun encode(calls: List<VoiceCallRecord>): String = JSONArray().also { array ->
            calls.forEach { call ->
                array.put(JSONObject().apply {
                    put("id", call.id)
                    put("startedAtMs", call.startedAtMs)
                    call.conversationId?.let { put("conversationId", it) }
                    call.endedAtMs?.let { put("endedAtMs", it) }
                    call.title?.let { put("title", it) }
                    put("transcript", JSONArray().also { entries ->
                        call.transcript.forEach { entry ->
                            entries.put(JSONObject().apply {
                                put("role", entry.role)
                                put("text", entry.text)
                                put("timestampMs", entry.timestampMs)
                                put("complete", entry.complete)
                                entry.latency?.let { put("latency", it.json()) }
                                entry.metrics?.let { put("metrics", it.json()) }
                                entry.replyId?.let { put("replyId", it) }
                                entry.delivery?.let { put("delivery", it.json()) }
                                put("generationComplete", entry.generationComplete)
                                put("origin", entry.origin.name)
                                if (entry.actions.isNotEmpty()) put("actions", JSONArray().also { actions ->
                                    entry.actions.forEach { action -> actions.put(JSONObject().put("name", action.name)
                                        .put("message", action.message).put("succeeded", action.succeeded)) }
                                })
                            })
                        }
                    })
                    call.taskStatus?.let { task ->
                        put("taskStatus", JSONObject().apply {
                            put("state", task.state.name)
                            put("completedSteps", JSONArray(task.completedSteps))
                            put("pendingSteps", JSONArray(task.pendingSteps))
                        })
                    }
                })
            }
        }.toString()

        internal fun decode(value: String): List<VoiceCallRecord> {
            if (value.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val transcript = item.optJSONArray("transcript")?.let { entries ->
                        (0 until entries.length()).mapNotNull { entryIndex ->
                            entries.optJSONObject(entryIndex)?.let { entry ->
                                TranscriptEntry(
                                    role = entry.optString("role"),
                                    text = entry.optString("text"),
                                    timestampMs = entry.optLong("timestampMs"),
                                    complete = entry.optBoolean("complete", true),
                                    latency = com.battlesbudz.jarvis.v2.diagnostics.TurnLatency.read(entry.optJSONObject("latency")),
                                    metrics = com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.read(entry.optJSONObject("metrics")),
                                    replyId = entry.optString("replyId").takeIf { it.isNotBlank() },
                                    delivery = readSpeechDelivery(entry.optJSONObject("delivery"), entry.optString("replyId").takeIf { it.isNotBlank() }),
                                    generationComplete = entry.optBoolean("generationComplete", entry.optBoolean("complete", true)),
                                    actions = entry.optJSONArray("actions")?.let { actions -> (0 until actions.length()).mapNotNull { i ->
                                        actions.optJSONObject(i)?.let { VoiceActionOutcome(it.optString("name"), it.optString("message"), it.optBoolean("succeeded")) }
                                    } }.orEmpty(),
                                    origin = runCatching { TranscriptOrigin.valueOf(entry.optString("origin", TranscriptOrigin.SPOKEN.name)) }
                                        .getOrDefault(TranscriptOrigin.SPOKEN)
                                )
                            }
                        }
                    }.orEmpty()
                    val taskObject = item.optJSONObject("taskStatus")
                    val task = taskObject?.let {
                        runCatching {
                            VoiceTaskStatus(
                                state = VoiceTaskState.valueOf(it.optString("state")),
                                completedSteps = it.optJSONArray("completedSteps").toStringList(),
                                pendingSteps = it.optJSONArray("pendingSteps").toStringList()
                            )
                        }.getOrNull()
                    }
                    VoiceCallRecord(
                        id = id,
                        startedAtMs = item.optLong("startedAtMs"),
                        endedAtMs = item.optLong("endedAtMs").takeIf { item.has("endedAtMs") },
                        title = item.optString("title").takeIf { it.isNotBlank() },
                        transcript = transcript,
                        taskStatus = task,
                        conversationId = item.optString("conversationId").takeIf { it.isNotBlank() }
                    )
                }
            }.getOrDefault(emptyList())
        }

        private fun JSONArray?.toStringList(): List<String> = if (this == null) {
            emptyList()
        } else {
            (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
        }
    }
}
