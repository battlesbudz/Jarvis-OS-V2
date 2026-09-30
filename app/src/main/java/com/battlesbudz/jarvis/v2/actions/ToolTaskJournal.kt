package com.battlesbudz.jarvis.v2.actions

/** Only normalized native requests and short receipts belong here, never raw tool payloads. */
data class ToolTaskJournal(
    val attempts: List<ToolTaskAttempt> = emptyList(),
    val groups: List<ToolTaskGroup> = emptyList(),
    val approvals: List<ActionApprovalRequest> = emptyList(),
    val grants: List<ToolActionGrant> = emptyList(),
    val events: List<ToolTaskEvent> = emptyList(),
    val activeQuestionId: String? = null
)
data class ToolTaskGroup(
    val id: String, val conversationId: String, val summary: String, val attemptIds: List<String>,
    val createdAtMs: Long, val expiresAtMs: Long, val cancelled: Boolean = false,
    val resumeAfterRestart: Boolean = true
)
enum class ToolAuthority { USER_REQUEST, EXACT_APPROVAL, ROUTINE }
data class ToolActionGrant(
    val id: String, val routineId: String, val provider: String, val schemaVersion: Int,
    val requests: List<ActionRequest>, val createdAtMs: Long, val expiresAtMs: Long, val revoked: Boolean = false
)
enum class ToolTaskEventKind { ADMITTED, DISPATCHED, RECEIPT, APPROVAL_REQUESTED, RECOVERED, CANCELLED, GRANT_REVOKED, RECONCILED }
data class ToolTaskEvent(val attemptId: String, val generation: Long, val kind: ToolTaskEventKind, val atMs: Long)

internal fun ToolTaskState.isTerminal() = this in setOf(
    ToolTaskState.SUCCEEDED, ToolTaskState.FAILED, ToolTaskState.CANCELLED, ToolTaskState.UNKNOWN_OUTCOME)
internal fun ActionRequest.frozen() = copy(arguments = arguments.toMap())
internal fun ToolTaskJournal.frozen() = copy(
    attempts = attempts.map { it.copy(request = it.request.frozen()) },
    groups = groups.map { it.copy(attemptIds = it.attemptIds.toList()) },
    approvals = approvals.map { it.copy(action = it.action.frozen()) },
    grants = grants.map { it.copy(requests = it.requests.map { request -> request.frozen() }) }, events = events.toList())

/** No grant can expand these three tools into arbitrary or consequential operations. */
internal fun ActionRequest.isRoutineEligible() = when (name) {
    "read_battery" -> arguments.isEmpty()
    "set_volume" -> arguments.keys == setOf("level") && arguments["level"]?.toIntOrNull()?.let { it in 0..100 } == true
    "open_app" -> arguments.keys == setOf("app") && !arguments["app"].isNullOrBlank() && arguments.getValue("app").length <= 512
    else -> false
}
