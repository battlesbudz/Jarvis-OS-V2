package com.battlesbudz.jarvis.v2.voice

enum class VoiceSessionState {
    PASSIVE_LISTENING,
    ACTIVELY_LISTENING,
    PROCESSING,
    EXECUTING_ACTION,
    SPEAKING,
    WAITING_FOR_CONFIRMATION,
    INTERRUPTED,
    ENDED
}

enum class VoiceTaskState { COMPLETED, FAILED, CANCELLED, INTERRUPTED, WAITING_FOR_USER }

data class VoiceTaskStatus(
    val state: VoiceTaskState,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList()
)

data class VoiceCallRecord(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val title: String? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val taskStatus: VoiceTaskStatus? = null
)

data class TranscriptEntry(
    val role: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val complete: Boolean = true
)

