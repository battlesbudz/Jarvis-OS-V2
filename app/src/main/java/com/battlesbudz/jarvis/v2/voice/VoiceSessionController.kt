package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Owns Voice Call lifecycle and durable checkpoints. It does not run models,
 * speak, or execute tools; those are injected through the surrounding runtime.
 */
class VoiceSessionController(
    private val store: VoiceCallStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var activeCall: VoiceCallRecord? = null

    fun beginCall(): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        return VoiceCallRecord(UUID.randomUUID().toString(), nowMs()).also {
            activeCall = it
            _state.value = VoiceSessionState.ACTIVELY_LISTENING
            checkpoint()
        }
    }

    fun appendTranscript(role: String, text: String, complete: Boolean = true) {
        val call = requireActiveCall()
        val entries = call.transcript.toMutableList()
        val previous = entries.lastOrNull()
        if (previous?.role == role && !previous.complete) {
            entries[entries.lastIndex] = previous.copy(text = text, complete = complete, timestampMs = nowMs())
        } else {
            entries += TranscriptEntry(role, text, nowMs(), complete)
        }
        activeCall = call.copy(transcript = entries)
        checkpoint()
    }

    fun currentTranscript(): List<TranscriptEntry> = activeCall?.transcript.orEmpty()

    /** Starts a new linked session with the prior call's transcript as context. */
    fun resumeCall(call: VoiceCallRecord): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        return VoiceCallRecord(
            id = UUID.randomUUID().toString(),
            startedAtMs = nowMs(),
            transcript = call.transcript,
            taskStatus = call.taskStatus
        ).also {
            activeCall = it
            _state.value = VoiceSessionState.ACTIVELY_LISTENING
            checkpoint()
        }
    }

    fun setState(state: VoiceSessionState) {
        requireActiveCall()
        _state.value = state
        checkpoint()
    }

    fun updateTask(status: VoiceTaskStatus) {
        activeCall = requireActiveCall().copy(taskStatus = status)
        checkpoint()
    }

    fun interrupt(): VoiceCallRecord {
        val call = requireActiveCall()
        _state.value = VoiceSessionState.INTERRUPTED
        activeCall = call.copy(taskStatus = call.taskStatus?.let {
            if (it.state == VoiceTaskState.COMPLETED || it.state == VoiceTaskState.CANCELLED) it
            else it.copy(state = VoiceTaskState.INTERRUPTED)
        })
        return endInternal()
    }

    fun end(title: String? = null): VoiceCallRecord {
        requireActiveCall()
        return endInternal(title)
    }

    private fun endInternal(title: String? = null): VoiceCallRecord {
        val call = requireActiveCall()
        val ended = call.copy(endedAtMs = nowMs(), title = title ?: deriveTitle(call.transcript))
        store.save(ended)
        activeCall = null
        _state.value = VoiceSessionState.PASSIVE_LISTENING
        return ended
    }

    private fun checkpoint() {
        activeCall?.let(store::save)
    }

    private fun requireActiveCall(): VoiceCallRecord =
        requireNotNull(activeCall) { "No active Voice Call." }

    /** Keep the history list useful without asking the user to name a call. */
    private fun deriveTitle(entries: List<TranscriptEntry>): String? {
        val words = entries
            .filter { it.role == "You" }
            .flatMap { Regex("[A-Za-z]{4,}").findAll(it.text.lowercase()).map { match -> match.value }.toList() }
            .filterNot { it in setOf("that", "this", "what", "with", "could", "would", "please", "jarvis") }
        if (words.isEmpty()) return null
        return words.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?.replaceFirstChar { it.uppercase() }
    }
}
