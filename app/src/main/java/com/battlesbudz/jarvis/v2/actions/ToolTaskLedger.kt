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
    val resultOutcome: ExecutionResult.Outcome? = null,
    val groupId: String? = null,
    val stepId: String = id,
    val provider: String = "native",
    val schemaVersion: Int = MobileToolCatalog.VERSION,
    val authority: ToolAuthority = ToolAuthority.USER_REQUEST,
    val grantId: String? = null,
    val approvalId: String? = null,
    val actionRevision: Long = 0,
    val reconciled: Boolean = false
)

class ToolTaskLedger(
    internal val store: ToolTaskStore = InMemoryToolTaskStore(),
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
            if (current == null || current.generation != expectedGeneration || current.state.isTerminal() ||
                current.groupId != null && state == ToolTaskState.RUNNING) attempts
            else {
                next = current.copy(generation = Math.addExact(current.generation, 1), state = state,
                    updatedAtMs = maxOf(current.updatedAtMs, now()), result = result, resultOutcome = resultOutcome)
                attempts.map { if (it.id == id) checkNotNull(next) else it }
            }
        }
        return next
    }

    fun snapshot(): List<ToolTaskAttempt> = store.read()

    fun journal(): ToolTaskJournal = store.readJournal()

    /** Admit the full ordered plan once, before its first native effect. */
    fun admit(requests: List<ActionRequest>, conversationId: String,
        authority: ToolAuthority = ToolAuthority.USER_REQUEST, grantId: String? = null,
        validForMs: Long = 120_000): ToolTaskGroup {
        require(requests.size in 1..3 && requests.all { it.isRoutineEligible() })
        require(conversationId.length in 1..256 && validForMs > 0)
        require(authority != ToolAuthority.ROUTINE || grantId != null)
        val at = now()
        val groupId = UUID.randomUUID().toString()
        val attempts = requests.map { request -> ToolTaskAttempt(UUID.randomUUID().toString(), 0,
            ToolTaskState.QUEUED, request.frozen(), at, at, groupId = groupId, authority = authority, grantId = grantId) }
        val group = ToolTaskGroup(groupId, conversationId, requests.joinToString(", ") { it.name }.take(256),
            attempts.map { it.id }, at, Math.addExact(at, validForMs))
        store.updateJournal { j ->
            if (authority == ToolAuthority.ROUTINE) require(attempts.all { granted(it, j, at) })
            j.copy(attempts = j.attempts + attempts, groups = j.groups + group,
                events = j.events + attempts.map { ToolTaskEvent(it.id, 0, ToolTaskEventKind.ADMITTED, at) })
        }
        return group
    }

    fun grant(routineId: String, requests: List<ActionRequest>, expiresAtMs: Long): ToolActionGrant {
        val at = now()
        require(routineId.length in 1..256 && requests.size in 1..3 && requests.all { it.isRoutineEligible() } && expiresAtMs > at)
        val grant = ToolActionGrant(UUID.randomUUID().toString(), routineId, "native", MobileToolCatalog.VERSION,
            requests.map { it.frozen() }, at, expiresAtMs)
        store.updateJournal { it.copy(grants = it.grants + grant) }
        return grant
    }

    /** Disabling a routine pauses only future steps relying on that grant. */
    fun revokeGrant(id: String): Boolean {
        var changed = false
        store.updateJournal { j ->
            val grant = j.grants.find { it.id == id && !it.revoked } ?: return@updateJournal j
            changed = true
            val paused = j.attempts.map { a -> if (a.grantId == id && !a.state.isTerminal() && a.state != ToolTaskState.RUNNING)
                a.advance(ToolTaskState.PAUSED, "The routine's permission was revoked.") else a }
            val affected = paused.filter { a -> j.attempts.find { it.id == a.id } != a }
            j.copy(grants = j.grants.map { if (it.id == grant.id) it.copy(revoked = true) else it }, attempts = paused,
                events = j.events + affected.map { it.event(ToolTaskEventKind.GRANT_REVOKED) })
        }
        return changed
    }

    fun requestApproval(id: String, expectedGeneration: Long, provider: String, schemaVersion: Int): AuthorizedDispatch {
        var dispatch: AuthorizedDispatch? = null
        store.updateJournal { j ->
            val a = j.attempts.find { it.id == id } ?: error("Unknown task attempt.")
            require(a.generation == expectedGeneration && a.state in setOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.WAITING_APPROVAL, ToolTaskState.PAUSED))
            require(provider == a.provider && schemaVersion == a.schemaVersion)
            val taskId = a.groupId ?: a.id
            val approval = newApproval(j, taskId, a.stepId, provider, a.request, schemaVersion, now())
            val waiting = a.advance(ToolTaskState.WAITING_APPROVAL).copy(authority = ToolAuthority.EXACT_APPROVAL,
                approvalId = approval.id, actionRevision = approval.revision)
            dispatch = AuthorizedDispatch(waiting, approval)
            j.copy(attempts = j.attempts.map { if (it.id == id) waiting else it },
                approvals = replaceApprovals(j, approval), activeQuestionId = approval.id,
                events = j.events + waiting.event(ToolTaskEventKind.APPROVAL_REQUESTED))
        }
        return checkNotNull(dispatch)
    }

    /** An edited target invalidates the old choice; it cannot inherit routine or user authority. */
    fun revise(id: String, expectedGeneration: Long, request: ActionRequest): ToolTaskAttempt? {
        require(request.isRoutineEligible())
        var revised: ToolTaskAttempt? = null
        store.updateJournal { j ->
            val a = j.attempts.find { it.id == id && it.generation == expectedGeneration &&
                !it.state.isTerminal() && it.state != ToolTaskState.RUNNING } ?: return@updateJournal j
            revised = a.advance(ToolTaskState.QUEUED).copy(request = request.frozen(), authority = ToolAuthority.EXACT_APPROVAL,
                actionRevision = Math.addExact(a.actionRevision, 1), approvalId = null)
            j.copy(attempts = j.attempts.map { if (it.id == id) checkNotNull(revised) else it },
                approvals = j.approvals.map { if (it.id == a.approvalId && !it.consumed)
                    it.copy(consumed = true, decision = ApprovalDecision.STALE) else it },
                activeQuestionId = j.activeQuestionId?.takeUnless { it == a.approvalId })
        }
        return revised
    }

    /** Exact action eligibility and approval consumption are committed atomically. */
    fun claim(id: String, expectedGeneration: Long, provider: String = "native",
        schemaVersion: Int = MobileToolCatalog.VERSION, approval: ActionApprovalRequest? = null,
        spoken: Boolean = false): ToolTaskAttempt? {
        var running: ToolTaskAttempt? = null
        store.updateJournal { j ->
            val a = j.attempts.find { it.id == id } ?: return@updateJournal j
            if (a.generation != expectedGeneration || a.state !in setOf(ToolTaskState.QUEUED, ToolTaskState.READY, ToolTaskState.WAITING_APPROVAL) ||
                a.provider != provider || a.schemaVersion != schemaVersion || !eligible(a, j, now())) return@updateJournal j
            val approved = if (a.authority == ToolAuthority.EXACT_APPROVAL) {
                val saved = j.approvals.find { it.id == a.approvalId } ?: return@updateJournal j
                if (spoken && (j.activeQuestionId != saved.id || j.approvals.count { !it.consumed } != 1)) return@updateJournal j
                if (approval != saved || saved.consumed || saved.taskId != (a.groupId ?: a.id) || saved.stepId != a.stepId ||
                    saved.provider != provider || saved.schemaVersion != schemaVersion || saved.action != a.request ||
                    saved.revision != a.actionRevision || saved.fingerprint != approvalFingerprint(saved.taskId, saved.stepId,
                        provider, a.request, schemaVersion, a.actionRevision)) return@updateJournal j
                saved.copy(consumed = true, decision = ApprovalDecision.APPROVED)
            } else null
            running = a.advance(ToolTaskState.RUNNING)
            j.copy(attempts = j.attempts.map { if (it.id == id) checkNotNull(running) else it },
                approvals = j.approvals.map { if (it.id == approved?.id) checkNotNull(approved) else it },
                activeQuestionId = j.activeQuestionId?.takeUnless { it == approved?.id },
                events = j.events + checkNotNull(running).event(ToolTaskEventKind.DISPATCHED))
        }
        return running
    }

    fun finish(running: ToolTaskAttempt, result: ExecutionResult): ToolTaskAttempt? {
        var finished: ToolTaskAttempt? = null
        store.updateJournal { j ->
            val a = j.attempts.find { it.id == running.id } ?: return@updateJournal j
            if (a.generation != running.generation || a.state != ToolTaskState.RUNNING) return@updateJournal j
            val state = when (result.outcome) {
                ExecutionResult.Outcome.SUCCEEDED -> ToolTaskState.SUCCEEDED
                ExecutionResult.Outcome.UNKNOWN_COMPLETION -> ToolTaskState.UNKNOWN_OUTCOME
                else -> ToolTaskState.FAILED
            }
            finished = a.advance(state, result.message.take(2048), result.outcome)
            j.copy(attempts = j.attempts.map { if (it.id == a.id) checkNotNull(finished) else it },
                events = j.events + checkNotNull(finished).event(ToolTaskEventKind.RECEIPT))
        }
        return finished
    }

    fun cancelGroup(id: String): Boolean {
        var changed = false
        store.updateJournal { j ->
            val group = j.groups.find { it.id == id && !it.cancelled } ?: return@updateJournal j
            changed = true
            val attempts = j.attempts.map { a -> if (a.groupId == id && !a.state.isTerminal() && a.state != ToolTaskState.RUNNING)
                a.advance(ToolTaskState.CANCELLED, "Cancelled before this action started.") else a }
            val ids = attempts.filter { it.groupId == id }.mapNotNullTo(hashSetOf()) { it.approvalId }
            j.copy(groups = j.groups.map { if (it.id == id) group.copy(cancelled = true) else it }, attempts = attempts,
                approvals = j.approvals.map { if (it.id in ids && !it.consumed) it.copy(consumed = true, decision = ApprovalDecision.STALE) else it },
                activeQuestionId = j.activeQuestionId?.takeUnless { it in ids },
                events = j.events + attempts.filter { a -> j.attempts.find { it.id == a.id } != a }.map { it.event(ToolTaskEventKind.CANCELLED) })
        }
        return changed
    }

    /** Records a user's acknowledgement without converting an unknown effect into a retry. */
    fun reconcileUnknown(id: String, expectedGeneration: Long): Boolean {
        var changed = false
        store.updateJournal { j ->
            val a = j.attempts.find { it.id == id && it.generation == expectedGeneration &&
                it.state == ToolTaskState.UNKNOWN_OUTCOME && !it.reconciled } ?: return@updateJournal j
            changed = true
            val reconciled = a.copy(generation = Math.addExact(a.generation, 1), reconciled = true,
                updatedAtMs = maxOf(a.updatedAtMs, now()))
            j.copy(attempts = j.attempts.map { if (it.id == id) reconciled else it },
                events = j.events + reconciled.event(ToolTaskEventKind.RECONCILED))
        }
        return changed
    }

    fun pauseExpiredGroups() {
        store.updateJournal { j ->
            val expired = j.groups.filter { now() >= it.expiresAtMs || now() < it.createdAtMs }.mapTo(hashSetOf()) { it.id }
            val choices = j.attempts.filter { it.groupId in expired }.mapNotNullTo(hashSetOf()) { it.approvalId }
            j.copy(attempts = j.attempts.map { a -> if (a.groupId in expired && !a.state.isTerminal() &&
                a.state !in setOf(ToolTaskState.RUNNING, ToolTaskState.PAUSED))
                a.advance(ToolTaskState.PAUSED, "The earlier request expired. Please ask again if still needed.") else a },
                approvals = j.approvals.map { if (it.id in choices && !it.consumed) it.copy(consumed = true, decision = ApprovalDecision.STALE) else it },
                activeQuestionId = j.activeQuestionId?.takeUnless { it in choices })
        }
    }

    /** Invoke once at process-runtime creation, never while another owner is dispatching. */
    fun recoverAfterRestart(): List<ToolTaskAttempt> {
        val j = store.readJournal()
        // Old attempt-only journals have no safe resumption context.
        if (j.groups.isEmpty() && j.approvals.isEmpty() && j.grants.isEmpty()) return store.update { attempts -> attempts.map { current ->
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
        return store.updateJournal { before ->
            var recovered = before.attempts.map { a -> if (a.state == ToolTaskState.RUNNING)
                a.advance(ToolTaskState.UNKNOWN_OUTCOME, "Jarvis restarted before this action's completion was recorded.",
                    ExecutionResult.Outcome.UNKNOWN_COMPLETION) else a }
            val context = before.copy(attempts = recovered)
            recovered = recovered.map { a ->
                if (a.state.isTerminal() || a.state == ToolTaskState.PAUSED) a
                else if (a.authority == ToolAuthority.EXACT_APPROVAL && a.groupId != null && eligible(a, context, now(), checkDependencies = false))
                    if (a.state == ToolTaskState.WAITING_APPROVAL) a else a.advance(ToolTaskState.WAITING_APPROVAL)
                else if (a.groupId != null && eligible(a, context, now(), checkDependencies = false))
                    if (a.state == ToolTaskState.READY) a else a.advance(ToolTaskState.READY)
                else a.advance(ToolTaskState.PAUSED, "This action needs a fresh request or its permission restored.")
            }
            val invalidChoices = recovered.filter { it.state in setOf(ToolTaskState.PAUSED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME) }
                .mapNotNullTo(hashSetOf()) { it.approvalId }
            before.copy(attempts = recovered, activeQuestionId = null,
                approvals = before.approvals.map { if (it.id in invalidChoices && !it.consumed) it.copy(consumed = true, decision = ApprovalDecision.STALE) else it },
                events = before.events + recovered.filter { a -> before.attempts.find { it.id == a.id } != a }.map { it.event(ToolTaskEventKind.RECOVERED) })
        }.attempts
    }

    private fun eligible(a: ToolTaskAttempt, j: ToolTaskJournal, at: Long, checkDependencies: Boolean = true): Boolean {
        if (a.provider != "native" || a.schemaVersion != MobileToolCatalog.VERSION || !a.request.isRoutineEligible()) return false
        if (a.authority == ToolAuthority.ROUTINE && !granted(a, j, at)) return false
        val group = a.groupId?.let { id -> j.groups.find { it.id == id } } ?: return a.groupId == null
        if (group.cancelled || at >= group.expiresAtMs || at < group.createdAtMs) return false
        val previous = group.attemptIds.takeWhile { it != a.id }.mapNotNull { id -> j.attempts.find { it.id == id } }
        if (previous.any { it.state in setOf(ToolTaskState.FAILED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME, ToolTaskState.PAUSED) }) return false
        return !checkDependencies || previous.all { it.state == ToolTaskState.SUCCEEDED }
    }
    private fun granted(a: ToolTaskAttempt, j: ToolTaskJournal, at: Long) = j.grants.any {
        it.id == a.grantId && !it.revoked && at < it.expiresAtMs && it.provider == a.provider &&
            it.schemaVersion == a.schemaVersion && a.request in it.requests && a.request.isRoutineEligible()
    }
    private fun ToolTaskAttempt.advance(state: ToolTaskState, result: String? = null, outcome: ExecutionResult.Outcome? = null) =
        copy(generation = Math.addExact(generation, 1), state = state, updatedAtMs = maxOf(updatedAtMs, now()), result = result, resultOutcome = outcome)
    private fun ToolTaskAttempt.event(kind: ToolTaskEventKind) = ToolTaskEvent(id, generation, kind, updatedAtMs)
}
