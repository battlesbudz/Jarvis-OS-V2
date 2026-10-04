package com.battlesbudz.jarvis.v2.actions

/**
 * Validated actions commit dispatch intent before effects and typed receipts afterward. No retries.
 *
 * M1e (T08/T09): every tool family checks its remembered source access, its
 * live Android capability and the lock state at admission — and the live
 * capability and lock are re-checked immediately before dispatch. Denial or
 * revocation blocks dispatch on all adapters with a truthful receipt; no
 * adapter is touched. The gates are optional so existing callers (and the
 * JVM contract tests) keep their behavior; production wires the Android
 * probes.
 */
class JournaledActionPipeline(
    private val ledger: ToolTaskLedger,
    private val executor: MobileActionExecutor,
    private val sourceAccess: ToolSourceAccess? = null,
    private val capabilityProbe: ToolCapabilityProbe? = null,
    private val lockGate: DeviceLockGate? = null
) {
    fun execute(request: ActionRequest): ExecutionResult {
        val frozenRequest = request.copy(arguments = request.arguments.toMap())
        val validation = MobileActionValidator().validate(frozenRequest)
        if (validation is ActionValidation.Rejected) return ExecutionResult(ExecutionResult.Outcome.REJECTED_VALIDATION, validation.reason)
        gateCheck(frozenRequest)?.let { return it }
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
        // M1e: the approval path re-checks permission, capability and lock at
        // admission — a revocation or lock between approval and claim blocks
        // dispatch with an honest receipt instead of consuming the approval.
        gateCheck(attempt.request)?.let { return it }
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

    /**
     * M1e admission gate: invalid args are rejected by the validator before
     * this runs; here the remembered source access (denial/revocation/scope),
     * the live Android capability and the lock state are checked in order.
     * Returns null when dispatch may proceed.
     */
    private fun gateCheck(request: ActionRequest): ExecutionResult? {
        sourceAccess?.denial(request)?.let { return it }
        capabilityProbe?.missingReason(request)?.let { reason ->
            return ExecutionResult(ExecutionResult.Outcome.DENIED_PERMISSION, reason)
        }
        val gate = lockGate
        if (gate != null && gate.check(request) == LockVerdict.NEEDS_UNLOCK) {
            return ExecutionResult(ExecutionResult.Outcome.NEEDS_UNLOCK, gate.unlockMessage(request))
        }
        return null
    }

    private fun perform(running: ToolTaskAttempt, durableGroup: Boolean): ExecutionResult {
        // M1e: immediately before dispatch, re-check the live capability and
        // lock state — a revocation or lock change since admission must still
        // block the effect. The blocked attempt is saved terminal, never lost.
        gateCheck(running.request)?.let { blocked ->
            return try {
                save(running, blocked, durableGroup)
                blocked
            } catch (_: ToolTaskStorageException) { blocked }
        }
        val result = try { MobileActionPipeline(executor = executor).execute(running.request) }
        catch (error: Exception) {
            // Cancellation or a programming error can arrive after a synchronous Android effect.
            try { save(running, ExecutionResult(ExecutionResult.Outcome.UNKNOWN_COMPLETION,
                "Execution ended before completion could be confirmed."), durableGroup) }
            catch (_: ToolTaskStorageException) { /* Durable RUNNING will recover as unknown. */ }
            throw error
        }
        if (result.succeeded) sourceAccess?.recordGrant(running.request)
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
            // M1e: NEEDS_UNLOCK is terminal and honest — the user unlocks and
            // asks again; the attempt is never auto-retried.
            else -> ToolTaskState.FAILED
        }
        return ledger.transition(running.id, running.generation, state, result.message, result.outcome)
    }
}
