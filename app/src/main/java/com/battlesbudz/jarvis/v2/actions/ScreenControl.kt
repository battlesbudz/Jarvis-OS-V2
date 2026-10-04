package com.battlesbudz.jarvis.v2.actions

import java.util.UUID

/**
 * M1c screen control: compact observation, verified targets, session-scoped
 * grants, user-touch pause with idle resume, and a floating Stop overlay.
 *
 * This file is JVM-pure (no android.* imports) so the whole state machine is
 * unit-testable. The Android half ([ScreenControlService]) implements
 * [ScreenBridge] against the accessibility APIs.
 *
 * Verified-target rule: every screen mutation (tap/scroll/type) must name a
 * target ID and observation token from the latest [ScreenObservation]. A token
 * rotates on every observation, so anything observed before the current screen
 * state is stale and can never dispatch.
 */
data class ScreenNode(
    val id: String,
    val label: String,
    val role: String,
    val bounds: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false
)

data class ScreenObservation(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val observedAtMs: Long = System.currentTimeMillis()
) {
    /** Compact, model-readable snapshot. Bounded so receipts stay small. */
    fun compactText(token: String, maxNodes: Int = 64): String = buildString {
        appendLine("Screen: $packageName (${nodes.size} elements)")
        nodes.take(maxNodes).forEach { node ->
            appendLine("[${node.id}] ${node.role} \"${node.label.take(60)}\" (${node.bounds})")
        }
        if (nodes.size > maxNodes) appendLine("... ${nodes.size - maxNodes} more elements omitted")
        append("observation token: $token")
    }
}

/** Android-facing screen operations. JVM tests inject a fake; production uses the accessibility service. */
interface ScreenBridge {
    fun isAvailable(): Boolean
    fun observe(): ScreenObservation?
    fun tap(node: ScreenNode): Boolean
    fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean
    fun type(node: ScreenNode, text: String): Boolean
    fun showStopOverlay(taskLabel: String): Boolean
    fun hideStopOverlay()
}

/** Outcome of asking the session for a screen-control grant (D23/D26). */
sealed interface AdmitResult {
    data object Admitted : AdmitResult
    data object AlreadyAdmitted : AdmitResult
    data object NeedsApproval : AdmitResult
    data class Denied(val reason: String) : AdmitResult
}

/** Dispatch-time gate for screen mutations (T06). */
sealed interface DispatchGate {
    data object Allowed : DispatchGate
    data object NeedsAdmission : DispatchGate
    data object Paused : DispatchGate
    /** The user stopped touching the screen; the caller must re-observe before acting. No countdown. */
    data object ResumeReobserve : DispatchGate
    data object Stopped : DispatchGate
}

sealed interface TargetVerification {
    data class Verified(val node: ScreenNode) : TargetVerification
    data class Rejected(val reason: String) : TargetVerification
}

/**
 * Session-scoped screen-control grant. One grant per task group: admitting a
 * second group while one holds the lease is denied, and a later group never
 * silently inherits an expired session (D26). Manual touch pauses dispatch;
 * after [touchIdleMs] of no touch the next mutation must re-observe the screen
 * first, without any countdown (T06, D25).
 */
class ScreenControlSession(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val touchIdleMs: Long = 3000L
) {
    private var groupId: String? = null
    private var observation: ScreenObservation? = null
    private var token: String? = null
    private var touchActive = false
    private var pausedByTouch = false
    private var lastTouchMs = 0L
    private var stopRequested = false

    val isAdmitted: Boolean get() = groupId != null
    val currentToken: String? get() = token
    val isStopRequested: Boolean get() = stopRequested

    fun admit(groupId: String, userApproved: Boolean): AdmitResult {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        val current = this.groupId
        if (current != null) {
            return if (current == groupId) AdmitResult.AlreadyAdmitted
            else AdmitResult.Denied("Screen control is already in use by another task.")
        }
        if (!userApproved) return AdmitResult.NeedsApproval
        this.groupId = groupId
        stopRequested = false
        return AdmitResult.Admitted
    }

    /** Records a fresh observation and rotates the token, invalidating all older targets. */
    fun recordObservation(observation: ScreenObservation): String {
        val newToken = UUID.randomUUID().toString().replace("-", "").take(16)
        this.observation = observation
        this.token = newToken
        return newToken
    }

    /**
     * Verifies a mutation target against the latest observation. [requireNode]
     * returns a rejection reason when the node cannot take this action
     * (not clickable/editable/scrollable), or null when it can.
     */
    fun verifyTarget(
        targetId: String,
        token: String,
        requireNode: (ScreenNode) -> String?
    ): TargetVerification {
        val observed = observation
        if (observed == null || token != this.token) {
            return TargetVerification.Rejected(
                "That screen observation is stale — the screen changed. " +
                    "Call screen_observe again for fresh targets."
            )
        }
        val node = observed.nodes.firstOrNull { it.id == targetId }
            ?: return TargetVerification.Rejected(
                "Target $targetId is not on the current screen. " +
                    "Call screen_observe again for fresh targets."
            )
        requireNode(node)?.let { return TargetVerification.Rejected(it) }
        return TargetVerification.Verified(node)
    }

    fun noteTouchStart() {
        touchActive = true
    }

    fun noteTouchEnd() {
        if (touchActive) {
            touchActive = false
            pausedByTouch = true
            lastTouchMs = clock()
        }
    }

    fun dispatchGate(): DispatchGate {
        if (stopRequested) return DispatchGate.Stopped
        if (groupId == null) return DispatchGate.NeedsAdmission
        if (touchActive) return DispatchGate.Paused
        if (pausedByTouch) {
            if (clock() - lastTouchMs >= touchIdleMs) {
                pausedByTouch = false
                return DispatchGate.ResumeReobserve
            }
            return DispatchGate.Paused
        }
        return DispatchGate.Allowed
    }

    fun requestStop() {
        stopRequested = true
    }

    /** Releases the lease; a later group must admit again (D26). */
    fun release() {
        groupId = null
        observation = null
        token = null
        touchActive = false
        pausedByTouch = false
        stopRequested = false
    }
}
