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
    private var recentCallContext: List<TranscriptEntry> = emptyList()

    @Synchronized fun beginCall(): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        val now = nowMs()
        recentCallContext = store.list().filter { call ->
            call.transcript.any { it.complete } &&
                call.endedAtMs?.let { now - it in 0..(15 * 60 * 1000L) } == true
        }.maxByOrNull { it.endedAtMs ?: 0 }?.transcript.orEmpty()
            .filter { it.complete }.takeLast(6)
        return VoiceCallRecord(UUID.randomUUID().toString(), now).also {
            activeCall = it
            _state.value = VoiceSessionState.ACTIVELY_LISTENING
            checkpoint()
        }
    }

    @Synchronized fun appendTranscript(role: String, text: String, complete: Boolean = true,
                         latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null) {
        val call = requireActiveCall()
        val entries = call.transcript.toMutableList()
        val previous = entries.lastOrNull()
        if (previous?.role == role && !previous.complete) {
            entries[entries.lastIndex] = previous.copy(text = text, complete = complete, timestampMs = nowMs(), latency = latency ?: previous.latency)
        } else {
            entries += TranscriptEntry(role, text, nowMs(), complete, latency)
        }
        activeCall = call.copy(transcript = entries)
        if (complete) checkpoint() else store.saveProgress(requireActiveCall())
    }

    /** Late speech metrics can update only the reply carrying this measurement ID. */
    @Synchronized fun updateReplyLatency(latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency) {
        val call = activeCall ?: return
        val index = call.transcript.indexOfFirst { it.role == "Jarvis" && it.latency?.id == latency.id }
        if (index < 0) return
        val entries = call.transcript.toMutableList()
        entries[index] = entries[index].copy(latency = latency)
        activeCall = call.copy(transcript = entries)
        checkpoint()
    }

    @Synchronized fun currentCallId(): String? = activeCall?.id

    @Synchronized fun currentTranscript(): List<TranscriptEntry> = activeCall?.transcript.orEmpty()

    /** Background dialogue only: never imports task state or appends old entries to the new call. */
    @Synchronized fun conversationContext(): List<TranscriptEntry> =
        (recentCallContext + currentTranscript()).takeLast(8)

    /** Starts a new linked session with the prior call's transcript as context. */
    @Synchronized fun resumeCall(call: VoiceCallRecord): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        recentCallContext = emptyList()
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

    /** An asynchronous model load may finish after Stop or after another call starts. */
    @Synchronized fun setStateIfCurrent(callId: String, state: VoiceSessionState): Boolean {
        if (activeCall?.id != callId) return false
        if (_state.value == state) return true
        _state.value = state
        checkpoint()
        return true
    }

    @Synchronized fun setState(state: VoiceSessionState) {
        requireActiveCall()
        if (_state.value == state) return
        _state.value = state
        checkpoint()
    }

    @Synchronized fun updateTask(status: VoiceTaskStatus) {
        activeCall = requireActiveCall().copy(taskStatus = status)
        checkpoint()
    }

    @Synchronized fun interrupt(): VoiceCallRecord {
        val call = requireActiveCall()
        _state.value = VoiceSessionState.INTERRUPTED
        activeCall = call.copy(taskStatus = call.taskStatus?.let {
            if (it.state == VoiceTaskState.COMPLETED || it.state == VoiceTaskState.CANCELLED) it
            else it.copy(state = VoiceTaskState.INTERRUPTED)
        })
        return endInternal()
    }

    @Synchronized fun end(title: String? = null): VoiceCallRecord {
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

    /** Preserve the latest partial at Pause, external handoff, and turn cleanup. */
    @Synchronized fun flushCheckpoint() = checkpoint()

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
