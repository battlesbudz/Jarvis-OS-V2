package com.battlesbudz.jarvis.v2.runtime

import com.battlesbudz.jarvis.v2.actions.ActionApprovalStore
import com.battlesbudz.jarvis.v2.actions.ActionRequest
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.actions.ExecutionResult
import com.battlesbudz.jarvis.v2.actions.JournaledActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.actions.ToolTaskLedger
import com.battlesbudz.jarvis.v2.actions.ToolTaskState
import com.battlesbudz.jarvis.v2.actions.ToolTaskStorageException
import com.battlesbudz.jarvis.v2.chat.ActionReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns durable phone-task admission, approval, recovery and conversation projections.
 * Android execution and conversation storage enter through explicit ports; this component
 * never owns a model, microphone, activity or the wider application runtime.
 */
internal class PhoneTaskCoordinator(
    private val scope: CoroutineScope,
    ledgerFactory: () -> ToolTaskLedger,
    private val isDeviceLocked: () -> Boolean,
    private val createExecutor: () -> MobileActionExecutor,
    private val conversationExists: (String) -> Boolean,
    private val projectReply: (conversationId: String, replyId: String, text: String, receipts: List<ActionReceipt>) -> Unit
) {
    private val ledger by lazy(ledgerFactory)
    val tasks = MutableStateFlow<ToolTaskJournal?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Startup recovery is owned by the application scope, never an activity. */
    fun recoverAfterRestart() {
        scope.launch(Dispatchers.Main) {
            try {
                ledger.recoverAfterRestart()
                ledger.journal().groups.filter { group -> ledger.snapshot().any {
                    it.groupId == group.id && it.state != ToolTaskState.SUCCEEDED
                } }.forEach { projectPhoneTask(it.id, recovered = true) }
                resumePhoneTasksAfterUnlock()
            } catch (_: ToolTaskStorageException) {
                error.value = "The action journal is unavailable. Phone actions are paused."
            } finally { refreshPhoneTasks() }
        }
    }

    fun admitPhoneTask(plan: ActionTurnPlan.Ready, conversationId: String): String? = try {
        ledger.admit(plan.steps.map { it.request }, conversationId,
            resumeAfterRestart = plan.batteryCondition == null).id.also { refreshPhoneTasks() }
    } catch (_: ToolTaskStorageException) {
        error.value = "The action journal is unavailable. No new phone action was started."
        null
    }

    fun refreshPhoneTasks() {
        try { tasks.value = ledger.journal() }
        catch (_: ToolTaskStorageException) {
            error.value = "The action journal is unavailable. Phone actions are paused."
        }
    }

    fun cancelPhoneTask(groupId: String) {
        try { ledger.cancelGroup(groupId) }
        catch (_: ToolTaskStorageException) {
            error.value = "I couldn't save the cancellation. No further action will start in this turn."
        }
        refreshPhoneTasks()
    }

    fun executePhoneAction(
        request: ActionRequest,
        executor: MobileActionExecutor,
        groupId: String? = null,
        stepIndex: Int = 0
    ): ExecutionResult = try {
        val pipeline = JournaledActionPipeline(ledger, executor, ::refreshPhoneTasks)
        if (groupId == null) pipeline.execute(request) else {
            val journal = ledger.journal()
            val id = journal.groups.find { it.id == groupId }?.attemptIds?.getOrNull(stepIndex)
            val attempt = journal.attempts.find { it.id == id }
            if (attempt == null) ExecutionResult(false,
                "The saved action no longer matches this request. I didn't start it.")
            else pipeline.executeBound(attempt, request)
        }
    } catch (_: ToolTaskStorageException) {
        ExecutionResult(false,
            "The phone-action journal is unavailable, so I didn't start this action.")
    } finally { refreshPhoneTasks() }

    /** Re-evaluate only at process startup or foreground/unlock; no periodic memory polling. */
    fun resumePhoneTasksAfterUnlock() {
        scope.launch(Dispatchers.Main) {
            if (isDeviceLocked()) return@launch
            try {
                ledger.pauseExpiredGroups()
                val journal = ledger.journal()
                for (group in journal.groups) {
                    if (!conversationExists(group.conversationId)) {
                        ledger.cancelGroup(group.id)
                        continue
                    }
                    if (System.currentTimeMillis() >= group.expiresAtMs) continue
                    for (id in group.attemptIds) {
                        val attempt = ledger.get(id) ?: break
                        if (attempt.state == ToolTaskState.SUCCEEDED) continue
                        if (attempt.state != ToolTaskState.READY) break
                        val result = JournaledActionPipeline(ledger,
                            createExecutor(), ::refreshPhoneTasks).executeAttempt(attempt)
                        projectPhoneTask(group.id, recovered = true)
                        if (!result.succeeded) break
                    }
                }
            } catch (_: ToolTaskStorageException) {
                error.value = "The action journal is unavailable. Phone actions are paused."
            } finally { refreshPhoneTasks() }
        }
    }

    private fun projectPhoneTask(groupId: String, recovered: Boolean = false) {
        val j = ledger.journal()
        val group = j.groups.find { it.id == groupId } ?: return
        val attempts = group.attemptIds.mapNotNull { id -> j.attempts.find { it.id == id } }
        val receipts = attempts.mapNotNull { a -> a.result?.let {
            ActionReceipt(a.request.name, it, a.state == ToolTaskState.SUCCEEDED) } }
        val status = if (attempts.any { !it.state.isTerminalForUi() }) "Some steps are still waiting." else "Task finished."
        projectReply(group.conversationId, "phone-task:$groupId",
            (if (recovered) "Recovered phone task. " else "Phone task. ") + status, receipts)
    }

    fun phoneTaskAction(id: String, generation: Long, command: String) {
        scope.launch(Dispatchers.Main) {
            error.value = null
            try {
                ledger.pauseExpiredGroups()
                val a = ledger.get(id)?.takeIf { it.generation == generation } ?: return@launch
                val approvals = ActionApprovalStore(ledger.store)
                when (command) {
                    "approve" -> {
                        if (isDeviceLocked()) return@launch
                        val approval = a.approvalId?.let { approvals.get(it) } ?: return@launch
                        val result = JournaledActionPipeline(ledger,
                            createExecutor(), ::refreshPhoneTasks).executeAttempt(a, approval)
                        if (!result.succeeded) error.value = result.message
                    }
                    "deny" -> a.approvalId?.let { approvals.deny(it) }
                    "cancel" -> if (a.groupId != null) ledger.cancelGroup(a.groupId)
                        else ledger.cancelLegacyAttempt(a.id, a.generation)
                    "checked" -> if (ledger.reconcileUnknown(a.id, a.generation)) a.groupId?.let { ledger.cancelGroup(it) }
                    else -> return@launch
                }
                a.groupId?.let { projectPhoneTask(it) }
            } catch (_: ToolTaskStorageException) {
                error.value = "I couldn't save that decision. Please try again."
            } finally { refreshPhoneTasks() }
        }
    }

    private fun ToolTaskState.isTerminalForUi() = this in setOf(
        ToolTaskState.SUCCEEDED, ToolTaskState.FAILED,
        ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
}
