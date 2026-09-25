package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

enum class ToolTaskState {
    QUEUED, READY, RUNNING, WAITING_APPROVAL, WAITING_INPUT, WAITING_RESOURCE,
    PAUSED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME
}

data class ToolTaskAttempt(
    val id: String,
    val generation: Long,
    val state: ToolTaskState,
    val request: ActionRequest,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val result: String? = null
)

class ToolTaskLedger(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val attempts = linkedMapOf<String, ToolTaskAttempt>()

    fun create(request: ActionRequest, state: ToolTaskState = ToolTaskState.QUEUED): ToolTaskAttempt = synchronized(lock) {
        val at = now()
        ToolTaskAttempt(UUID.randomUUID().toString(), 0, state, request, at, at).also { attempts[it.id] = it }
    }

    fun get(id: String): ToolTaskAttempt? = synchronized(lock) { attempts[id] }

    fun transition(id: String, expectedGeneration: Long, state: ToolTaskState, result: String? = null): ToolTaskAttempt? = synchronized(lock) {
        val current = attempts[id] ?: return null
        if (current.generation != expectedGeneration || current.state.isTerminal()) return null
        val next = current.copy(generation = current.generation + 1, state = state, updatedAtMs = now(), result = result)
        attempts[id] = next
        next
    }

    fun snapshot(): List<ToolTaskAttempt> = synchronized(lock) { attempts.values.toList() }

    private fun ToolTaskState.isTerminal() = this in setOf(
        ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME
    )
}
