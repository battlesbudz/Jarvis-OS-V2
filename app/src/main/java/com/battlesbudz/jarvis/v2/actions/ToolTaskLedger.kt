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
    val result: String? = null,
    val resultOutcome: ExecutionResult.Outcome? = null
)

class ToolTaskLedger(
    private val store: ToolTaskStore = InMemoryToolTaskStore(),
    private val now: () -> Long = System::currentTimeMillis
) {

    fun create(request: ActionRequest, state: ToolTaskState = ToolTaskState.QUEUED): ToolTaskAttempt {
        val at = now()
        val created = ToolTaskAttempt(UUID.randomUUID().toString(), 0, state,
            request.copy(arguments = request.arguments.toMap()), at, at)
        store.update { it + created }
        return created
    }

    fun get(id: String): ToolTaskAttempt? = store.read().firstOrNull { it.id == id }

    fun transition(id: String, expectedGeneration: Long, state: ToolTaskState, result: String? = null,
        resultOutcome: ExecutionResult.Outcome? = null): ToolTaskAttempt? {
        var next: ToolTaskAttempt? = null
        store.update { attempts ->
            val current = attempts.firstOrNull { it.id == id }
            if (current == null || current.generation != expectedGeneration || current.state.isTerminal()) attempts
            else {
                next = current.copy(generation = Math.addExact(current.generation, 1), state = state,
                    updatedAtMs = maxOf(current.updatedAtMs, now()), result = result, resultOutcome = resultOutcome)
                attempts.map { if (it.id == id) checkNotNull(next) else it }
            }
        }
        return next
    }

    fun snapshot(): List<ToolTaskAttempt> = store.read()

    /** Invoke once at process-runtime creation, never while another owner is dispatching. */
    fun recoverAfterRestart(): List<ToolTaskAttempt> = store.update { attempts ->
        attempts.map { current ->
            when {
                current.state.isTerminal() || current.state == ToolTaskState.PAUSED -> current
                current.state == ToolTaskState.RUNNING -> current.copy(
                    generation = Math.addExact(current.generation, 1), state = ToolTaskState.UNKNOWN_OUTCOME,
                    updatedAtMs = maxOf(current.updatedAtMs, now()),
                    result = "Jarvis restarted before this action's completion was recorded.",
                    resultOutcome = ExecutionResult.Outcome.UNKNOWN_COMPLETION)
                else -> current.copy(generation = Math.addExact(current.generation, 1), state = ToolTaskState.PAUSED,
                    updatedAtMs = maxOf(current.updatedAtMs, now()))
            }
        }
    }

    private fun ToolTaskState.isTerminal() = this in setOf(
        ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME
    )
}
