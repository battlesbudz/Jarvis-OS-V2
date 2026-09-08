package com.battlesbudz.jarvis.v2.voice

/** External recorder ownership, independent of keyboard windows and Activity visibility. */
class MicrophoneInterruptionPolicy(private val quietMs: Long = 750) {
    private var interrupted = false
    private var quietSince: Long? = null
    fun update(busy: Boolean, nowMs: Long): Boolean {
        if (busy) {
            interrupted = true
            quietSince = null
        } else if (interrupted) {
            val since = quietSince ?: nowMs.also { quietSince = it }
            if (nowMs - since >= quietMs) { interrupted = false; quietSince = null }
        }
        return interrupted
    }
}
