package com.battlesbudz.jarvis.v2.voice

/** Call-scoped feedback for optional drafts; committed recognition and replies never wait on this budget. */
class VoiceWorkScheduler {
    private var nextAt = 0L
    private val outcomes = ArrayDeque<Boolean>()
    var reason = "ready"
        private set
    @Synchronized fun admit(now: Long, backlogMs: Long, playbackMs: Long, thermalStatus: Int): Boolean {
        reason = when {
            thermalStatus >= 2 -> "thermal_pressure"
            backlogMs > 200 -> "asr_backlog"
            playbackMs > 0 -> "answer_playback"
            now < nextAt -> "cooldown"
            else -> "ready"
        }
        if (reason != "ready") return false
        nextAt = now + 2000
        return true
    }
    @Synchronized fun outcome(now: Long, reused: Boolean, cancellationMs: Long) {
        outcomes.addLast(reused)
        while (outcomes.size > 8) outcomes.removeFirst()
        if (cancellationMs >= 500) nextAt = maxOf(nextAt, now + 30_000)
        else if (outcomes.size >= 3 && outcomes.count { it } * 3 < outcomes.size)
            nextAt = maxOf(nextAt, now + 15_000)
    }
}
