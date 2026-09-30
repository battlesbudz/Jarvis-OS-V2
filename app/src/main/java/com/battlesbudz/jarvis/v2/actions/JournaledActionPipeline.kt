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
        return perform(running, durableGroup = false)
    }

    fun executeAttempt(attempt: ToolTaskAttempt, approval: ActionApprovalRequest? = null): ExecutionResult {
        val running = try {
            ledger.claim(attempt.id, attempt.generation, approval = approval)
                ?: return ExecutionResult(false, "This action is no longer authorized or ready. I didn't start it.")
        } catch (_: ToolTaskStorageException) {
            return ExecutionResult(false, "I couldn't save this phone action, so I didn't start it.")
        }
        return perform(running, durableGroup = true)
    }

    fun executeBound(attempt: ToolTaskAttempt, reportedRequest: ActionRequest): ExecutionResult {
        if (!ActionTurnRunner(executor).same(attempt.request, reportedRequest)) return ExecutionResult(
            ExecutionResult.Outcome.REJECTED_VALIDATION, "The saved action no longer matches this request. I didn't start it.")
        // Execute the original literal target, preserving case-insensitive native-call matching.
        return executeAttempt(attempt)
    }

    private fun perform(running: ToolTaskAttempt, durableGroup: Boolean): ExecutionResult {
        val result = try { MobileActionPipeline(executor = executor).execute(running.request) }
        catch (error: Exception) {
            // Cancellation or a programming error can arrive after a synchronous Android effect.
            try { save(running, ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                "Execution ended before completion could be confirmed."), durableGroup) }
            catch (_: ToolTaskStorageException) { /* Durable RUNNING will recover as unknown. */ }
            throw error
        }
        return try {
            if (save(running, result, durableGroup) == null)
                ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION, "This phone action's completion could not be recorded.")
            else result
        } catch (_: ToolTaskStorageException) {
            ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                "The action ran, but I couldn't save its completion. I won't repeat it automatically.")
        }
    }

    private fun save(running: ToolTaskAttempt, result: ExecutionResult, durableGroup: Boolean): ToolTaskAttempt? {
        if (durableGroup) return ledger.finish(running, result)
        val state = when (result.outcome) {
            ExecutionResult.Outcome.SUCCEEDED -> ToolTaskState.SUCCEEDED
            ExecutionResult.Outcome.UNKNOWN_COMPLETION -> ToolTaskState.UNKNOWN_OUTCOME
            else -> ToolTaskState.FAILED
        }
        return ledger.transition(running.id, running.generation, state, result.message, result.outcome)
    }
}
