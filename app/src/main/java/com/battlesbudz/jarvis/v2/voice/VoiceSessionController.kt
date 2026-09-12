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
            .filter { it.complete || (it.role == "Jarvis" && it.forConversation() != null) }.takeLast(6)
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
            entries[entries.lastIndex] = previous.copy(text = text, complete = complete, generationComplete = complete, timestampMs = nowMs(), latency = latency ?: previous.latency)
        } else {
            entries += TranscriptEntry(role, text, nowMs(), complete, latency)
        }
        activeCall = call.copy(transcript = entries)
        if (complete) checkpoint() else store.saveProgress(requireActiveCall())
    }

    /** Stable reply IDs separate generation, audio delivery and tool receipts. */
    @Synchronized fun beginReply(callId: String, replyId: String) {
        if (activeCall?.id != callId) return
        val call = requireActiveCall()
        check(call.transcript.none { it.replyId == replyId })
        activeCall = call.copy(transcript = call.transcript + TranscriptEntry("Jarvis", "", nowMs(),
            complete = false, replyId = replyId, delivery = SpeechDelivery(replyId), generationComplete = false))
        checkpoint()
    }
    @Synchronized fun updateReplyText(callId: String, replyId: String, text: String, finished: Boolean = false,
        latency: com.battlesbudz.jarvis.v2.diagnostics.TurnLatency? = null) {
        if (activeCall?.id != callId) return // Late generation cannot rewrite an ended/replaced call.
        changeReply(callId, replyId, durable = finished) { entry ->
            if (entry.generationComplete) entry else entry.copy(text = text, generationComplete = finished,
                complete = finished && entry.delivery?.state == SpeechDeliveryState.COMPLETED,
                latency = latency ?: entry.latency)
        }
    }
    @Synchronized fun updateDelivery(callId: String, delivery: SpeechDelivery) {
        changeReply(callId, delivery.turnId) { entry ->
            val previous = entry.delivery
            if (previous?.terminal == true || delivery.revision <= (previous?.revision ?: -1)) entry
            else entry.copy(delivery = delivery,
                complete = entry.generationComplete && delivery.state == SpeechDeliveryState.COMPLETED)
        }
    }
    @Synchronized fun recordReplyAction(callId: String, replyId: String, outcome: VoiceActionOutcome) {
        changeReply(callId, replyId) { entry -> entry.copy(actions = entry.actions + outcome) }
    }
    private fun changeReply(callId: String, replyId: String, durable: Boolean = true,
        transform: (TranscriptEntry) -> TranscriptEntry) {
        // Final playback/atomic tool receipts may arrive after End, but never target a different call.
        // A deleted call is not recreated.
        val live = activeCall?.id == callId
        val call = if (live) activeCall else store.list().firstOrNull { it.id == callId }
        if (call == null) return
        val index = call.transcript.indexOfFirst { it.replyId == replyId }
        if (index < 0) return
        val entry = transform(call.transcript[index])
        if (entry == call.transcript[index]) return
        val entries = call.transcript.toMutableList().also { it[index] = entry }
        val updated = call.copy(transcript = entries)
        if (live) activeCall = updated
        if (durable || !live) store.save(updated) else store.saveProgress(updated)
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
        (recentCallContext + currentTranscript()).mapNotNull { it.forConversation() }.takeLast(8)

    /** Starts a new linked session with the prior call's transcript as context. */
    @Synchronized fun resumeCall(call: VoiceCallRecord): VoiceCallRecord {
        check(activeCall == null) { "A Voice Call is already active." }
        recentCallContext = emptyList()
        val latest = store.list().firstOrNull { it.id == call.id } ?: call
        return VoiceCallRecord(
            id = UUID.randomUUID().toString(),
            startedAtMs = nowMs(),
            transcript = latest.transcript.map { entry ->
                entry.delivery?.takeIf { !it.terminal }?.let {
                    entry.copy(delivery = it.copy(state = SpeechDeliveryState.INTERRUPTED), complete = false)
                } ?: entry
            },
            taskStatus = latest.taskStatus
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
