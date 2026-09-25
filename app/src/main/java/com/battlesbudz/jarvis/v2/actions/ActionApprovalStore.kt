package com.battlesbudz.jarvis.v2.actions

import java.security.MessageDigest
import java.util.UUID

enum class ApprovalDecision { APPROVED, DENIED, STALE, AMBIGUOUS }

data class ActionApprovalRequest(
    val id: String,
    val taskId: String,
    val stepId: String,
    val provider: String,
    val action: ActionRequest,
    val schemaVersion: Int,
    val revision: Long,
    val fingerprint: String,
    val createdAtMs: Long,
    val consumed: Boolean = false
)

class ActionApprovalStore(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val requests = linkedMapOf<String, ActionApprovalRequest>()
    private var activeQuestionId: String? = null

    fun request(taskId: String, stepId: String, provider: String, action: ActionRequest, schemaVersion: Int): ActionApprovalRequest = synchronized(lock) {
        requests.values.filter { !it.consumed && it.taskId == taskId && it.stepId == stepId }.forEach {
            requests[it.id] = it.copy(consumed = true)
        }
        val revision = (requests.values.filter { it.taskId == taskId && it.stepId == stepId }.maxOfOrNull { it.revision } ?: 0L) + 1
        val created = ActionApprovalRequest(
            UUID.randomUUID().toString(), taskId, stepId, provider, action, schemaVersion, revision,
            fingerprint(provider, action, schemaVersion, revision), now()
        )
        requests[created.id] = created
        activeQuestionId = created.id
        created
    }

    fun consume(id: String, fingerprint: String): ApprovalDecision = synchronized(lock) {
        val current = requests[id] ?: return ApprovalDecision.STALE
        if (current.consumed || current.fingerprint != fingerprint) return ApprovalDecision.STALE
        requests[id] = current.copy(consumed = true)
        if (activeQuestionId == id) activeQuestionId = null
        ApprovalDecision.APPROVED
    }

    fun deny(id: String): ApprovalDecision = synchronized(lock) {
        val current = requests[id] ?: return ApprovalDecision.STALE
        if (current.consumed) return ApprovalDecision.STALE
        requests[id] = current.copy(consumed = true)
        if (activeQuestionId == id) activeQuestionId = null
        ApprovalDecision.DENIED
    }

    fun spokenYes(): ApprovalDecision = synchronized(lock) {
        val id = activeQuestionId ?: return ApprovalDecision.AMBIGUOUS
        val open = requests.values.filter { !it.consumed }
        if (open.size != 1 || open.single().id != id) return ApprovalDecision.AMBIGUOUS
        val current = requests.getValue(id)
        requests[id] = current.copy(consumed = true)
        activeQuestionId = null
        ApprovalDecision.APPROVED
    }

    fun get(id: String): ActionApprovalRequest? = synchronized(lock) { requests[id] }

    private fun fingerprint(provider: String, action: ActionRequest, schemaVersion: Int, revision: Long): String {
        val canonical = buildString {
            append(provider).append('|').append(schemaVersion).append('|').append(revision).append('|').append(action.name)
            action.arguments.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
