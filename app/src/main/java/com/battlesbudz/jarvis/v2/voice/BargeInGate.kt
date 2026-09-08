package com.battlesbudz.jarvis.v2.voice

/** AEC is opportunistic; speech must survive a quiet playback probe before it can interrupt. */
class BargeInGate(private val settleMs: Long = 200, private val probeMs: Long = 700) {
    enum class Action { WAIT, PAUSE, RESET_DETECTOR, CONFIRM, RESUME }
    private var probeAt: Long? = null
    private var detectorReset = false
    private var cooldownUntil = 0L
    var confirmed = false
        private set
    fun update(speech: Boolean, playing: Boolean, nowMs: Long): Action {
        if (confirmed) return Action.CONFIRM
        val start = probeAt
        if (start != null) {
            if (nowMs - start < settleMs) return Action.WAIT
            if (!detectorReset) { detectorReset = true; return Action.RESET_DETECTOR }
            if (speech) { confirmed = true; return Action.CONFIRM }
            if (nowMs - start >= probeMs) {
                probeAt = null; detectorReset = false; cooldownUntil = nowMs + 1000
                return Action.RESUME
            }
            return Action.WAIT
        }
        if (!speech || nowMs < cooldownUntil) return Action.WAIT
        if (!playing) { confirmed = true; return Action.CONFIRM }
        probeAt = nowMs
        return Action.PAUSE
    }
}
