package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.presentation.AgentActivityKind
import com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot
import kotlin.math.PI
import kotlin.math.sin

/** Pure drawing policy. No clock, work dispatch, audio synthesis or approval authority. */
internal object WispMotion {
    const val mouthWidthScale = 2f
    const val mouthHeightScale = 1.65f
    const val smileHeightScale = 1.5f

    fun pulse(age: Float?, duration: Float): Float =
        if (age != null && age.isFinite() && age > 0f && age < duration)
            sin(age / duration * PI).toFloat() else 0f

    fun bounce(activity: WispActivity, age: Float, moving: Boolean): Float =
        if (moving && activity == WispActivity.SUCCESS) -3f * pulse(age, .72f) else 0f

    fun nod(age: Float?, moving: Boolean): Float = if (moving) 4f * pulse(age, .65f) else 0f

    fun mouthLevel(audioActivity: WispActivity?, level: Float, moving: Boolean): Float =
        if (moving && audioActivity == WispActivity.SPEAKING && level.isFinite()) level.coerceIn(0f, 1f) else 0f
}

/** A one-shot born while stopped/reduced-motion is consumed, never queued for resume. */
internal class WispOneShotAge(activeAtCreation: Boolean) {
    var age: Float = if (activeAtCreation) 0f else 120f
        private set

    fun consume() { age = 120f }
    fun advance(seconds: Float) {
        if (seconds.isFinite() && seconds > 0f) age = (age + seconds).coerceAtMost(120f)
    }
}

/** A nod means a newly admitted request was observed, never that the request succeeded.
 * Mounting/resuming history and a reference read returning to its parent cannot replay it.
 */
internal class WispReceptionTracker {
    private var conversation: String? = null
    private var newestOperation: Long = Long.MIN_VALUE
    private var observing = true

    fun suspend() { observing = false }

    /** Baseline the live source at the lifecycle boundary, before replayed flow collection.
     * Reference/failure IDs also fence their older parent turn from becoming a fresh receipt.
     */
    fun resume(snapshot: AgentActivitySnapshot?, conversationId: String) {
        val previous = if (conversation == conversationId) newestOperation else Long.MIN_VALUE
        conversation = conversationId
        newestOperation = maxOf(previous, snapshot?.takeIf { it.conversationId == conversationId }
            ?.operationId ?: Long.MIN_VALUE)
        observing = true
    }

    fun update(snapshot: AgentActivitySnapshot?, conversationId: String): Long? {
        if (!observing) return null
        val current = snapshot?.takeIf { it.conversationId == conversationId }
        if (conversation != conversationId) {
            conversation = conversationId
            newestOperation = current?.operationId ?: Long.MIN_VALUE
            return null
        }
        if (current == null || current.operationId <= newestOperation) return null
        newestOperation = current.operationId
        return current.operationId.takeIf { current.kind == AgentActivityKind.WORKING }
    }
}
