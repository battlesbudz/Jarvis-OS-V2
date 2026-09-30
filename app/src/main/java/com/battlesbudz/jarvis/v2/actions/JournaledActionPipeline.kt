package com.battlesbudz.jarvis.v2.actions

/** Validated actions commit dispatch intent before effects and typed receipts afterward. No retries. */
class JournaledActionPipeline(
    private val ledger: ToolTaskLedger,
    private val executor: MobileActionExecutor
) {
    fun execute(request: ActionRequest): ExecutionResult {
        val frozenRequest = request.copy(arguments = request.arguments.toMap())
        val validation = MobileActionValidator().validate(frozenRequest)
        if (validation is ActionValidation.Rejected) return ExecutionResult(ExecutionResult.Outcome.REJECTED_VALIDATION, validation.reason)
        val running = try {
            val queued = ledger.create(frozenRequest)
            ledger.transition(queued.id, queued.generation, ToolTaskState.RUNNING)
                ?: return ExecutionResult(false, "The phone action changed before it could start.")
        } catch (_: ToolTaskStorageException) {
            return ExecutionResult(false, "I couldn't save this phone action, so I didn't start it.")
        }
        val result = try { MobileActionPipeline(executor = executor).execute(frozenRequest) }
        catch (error: Exception) {
            // Cancellation or a programming error can arrive after a synchronous Android effect.
            try { ledger.transition(running.id, running.generation, ToolTaskState.UNKNOWN_OUTCOME,
                "Execution ended before completion could be confirmed.", ExecutionResult.Outcome.UNKNOWN_COMPLETION) }
            catch (_: ToolTaskStorageException) { /* Durable RUNNING will recover as unknown. */ }
            throw error
        }
        val state = when (result.outcome) {
            ExecutionResult.Outcome.SUCCEEDED -> ToolTaskState.SUCCEEDED
            ExecutionResult.Outcome.UNKNOWN_COMPLETION -> ToolTaskState.UNKNOWN_OUTCOME
            else -> ToolTaskState.FAILED
        }
        return try {
            if (ledger.transition(running.id, running.generation, state, result.message, result.outcome) == null)
                ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION, "This phone action's completion could not be recorded.")
            else result
        } catch (_: ToolTaskStorageException) {
            ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                "The action ran, but I couldn't save its completion. I won't repeat it automatically.")
        }
    }
}
