package com.battlesbudz.jarvis.v2.chat

import android.content.SharedPreferences
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ConversationMessage(
    val id: String,
    val role: String,
    val text: String,
    val spoken: Boolean = false,
    val callId: String? = null,
    val contextText: String = text,
    val complete: Boolean = true,
    val attachment: ChatAttachment? = null
)
data class ConversationThread(val id: String, val messages: List<ConversationMessage> = emptyList()) {
    val title: String get() = messages.firstOrNull { it.role == "You" }?.text?.take(60) ?: "New conversation"
}

/** One app-owned thread across text, calls, model switches and process recreation. */
class ConversationHistory(private val preferences: SharedPreferences) {
    private val threads = linkedMapOf<String, ConversationThread>()
    private val _current = MutableStateFlow(ConversationThread(UUID.randomUUID().toString()))
    val current = _current.asStateFlow()
    private var lastProgressSave = 0L
    init {
        runCatching {
            val saved = JSONArray(preferences.getString("threads", "[]"))
            for (i in 0 until saved.length()) {
                val t = saved.getJSONObject(i)
                val entries = t.getJSONArray("messages")
                val messages = (0 until entries.length()).map { n ->
                    val m = entries.getJSONObject(n)
                    ConversationMessage(m.getString("id"), m.getString("role"), m.getString("text"),
                        m.optBoolean("spoken"), m.optString("callId").takeIf { it.isNotBlank() },
                        m.optString("contextText", m.getString("text")), m.optBoolean("complete", true),
                        m.optJSONObject("attachment")?.let { a -> runCatching {
                            ChatAttachment(a.getString("uri"), AttachmentKind.valueOf(a.getString("kind")))
                        }.getOrNull() })
                }
                threads[t.getString("id")] = ConversationThread(t.getString("id"), messages)
            }
        }
        _current.value = threads[preferences.getString("active", null)] ?: threads.values.lastOrNull() ?: _current.value
        threads[_current.value.id] = _current.value
    }
    @Synchronized fun list(): List<ConversationThread> = threads.values.toList().asReversed()
    @Synchronized fun newConversation() {
        _current.value = ConversationThread(UUID.randomUUID().toString())
        threads[_current.value.id] = _current.value
        persist()
    }
    @Synchronized fun select(id: String) { threads[id]?.let { _current.value = it; persist() } }
    @Synchronized fun appendUser(text: String, attachment: ChatAttachment? = null): String {
        val id = UUID.randomUUID().toString()
        replace(_current.value.copy(messages = _current.value.messages + ConversationMessage(id, "You", text,
            contextText = text + (attachment?.let { "\n[${it.kind.name.lowercase()} attached to this message]" } ?: ""), attachment = attachment)))
        return id
    }
    @Synchronized fun updateReply(threadId: String, id: String, text: String, complete: Boolean) {
        val thread = threads[threadId] ?: return
        val message = ConversationMessage(id, "Jarvis", text, complete = complete,
            contextText = if (complete) text else "")
        val index = thread.messages.indexOfFirst { it.id == id }
        val entries = thread.messages.toMutableList()
        if (index < 0) entries += message else entries[index] = message
        replace(thread.copy(messages = entries), complete)
    }
    /** Call snapshots replace their own entries, including late playback receipts; never duplicate them. */
    @Synchronized fun syncCall(call: VoiceCallRecord) {
        val threadId = call.conversationId ?: return
        val thread = threads[threadId] ?: return
        val messages = call.transcript.mapIndexed { index, entry ->
            ConversationMessage("${call.id}:$index", entry.role, entry.text, entry.role == "You", call.id,
                entry.forConversation()?.text.orEmpty(), entry.complete)
        }
        val first = thread.messages.indexOfFirst { it.callId == call.id }
        val entries = thread.messages.filterNot { it.callId == call.id }.toMutableList()
        entries.addAll(if (first < 0) entries.size else first.coerceAtMost(entries.size), messages)
        replace(thread.copy(messages = entries), call.endedAtMs != null || call.transcript.lastOrNull()?.complete == true)
    }
    /** Older calls have no thread ID; import once when explicitly opened for text continuation. */
    @Synchronized fun openCall(call: VoiceCallRecord) {
        val id = call.conversationId ?: "legacy:${call.id}"
        val existing = threads.containsKey(id)
        if (!existing) threads[id] = ConversationThread(id)
        _current.value = threads.getValue(id)
        // Existing linked threads already receive authoritative store snapshots. A stale
        // history-screen selection must not roll back a late playback receipt.
        if (!existing) syncCall(call.copy(conversationId = id))
        persist()
    }
    @Synchronized fun removeCall(callId: String) {
        threads.values.toList().forEach { thread ->
            if (thread.messages.any { it.callId == callId })
                replace(thread.copy(messages = thread.messages.filterNot { it.callId == callId }))
        }
    }
    @Synchronized fun context(excludingCall: String? = null): List<ChatEntry> =
        _current.value.messages.filter { (excludingCall == null || it.callId != excludingCall) && it.contextText.isNotBlank() }
            .takeLast(24).map { ChatEntry(it.role, it.contextText) }

    private fun replace(thread: ConversationThread, durable: Boolean = true) {
        threads[thread.id] = thread
        if (_current.value.id == thread.id) _current.value = thread
        val now = System.currentTimeMillis()
        if (durable || now - lastProgressSave >= 500) { persist(); lastProgressSave = now }
    }
    private fun persist() {
        val array = JSONArray()
        threads.values.forEach { thread ->
            val messages = JSONArray()
            thread.messages.forEach { m -> messages.put(JSONObject().put("id", m.id).put("role", m.role)
                .put("text", m.text).put("spoken", m.spoken).put("callId", m.callId)
                .put("contextText", m.contextText).put("complete", m.complete)
                .put("attachment", m.attachment?.let { JSONObject().put("uri", it.uri).put("kind", it.kind.name) })) }
            array.put(JSONObject().put("id", thread.id).put("messages", messages))
        }
        preferences.edit().putString("active", _current.value.id).putString("threads", array.toString()).apply()
    }
}

/** Observe persisted/coalesced snapshots, never activity callbacks or provisional ASR hypotheses. */
class ConversationVoiceCallStore(private val delegate: VoiceCallStore, private val history: ConversationHistory) : VoiceCallStore {
    override fun list() = delegate.list()
    override fun save(call: VoiceCallRecord) { delegate.save(call); history.syncCall(call) }
    override fun delete(callId: String) { delegate.delete(callId); history.removeCall(callId) }
}
