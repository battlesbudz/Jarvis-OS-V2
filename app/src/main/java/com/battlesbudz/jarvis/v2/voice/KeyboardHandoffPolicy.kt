package com.battlesbudz.jarvis.v2.voice

/** Main-thread state machine. Keyboard preflight is bounded; actual recording keeps priority. */
class KeyboardHandoffPolicy(private val quietMs: Long = 750, private val reservationMs: Long = 2500) {
    private var visible = false
    private var holding = false
    private var sawRecording = false
    private var quietSince: Long? = null
    private var reservedAt: Long? = null
    fun update(keyboardVisible: Boolean, externalRecording: Boolean, interaction: Boolean, nowMs: Long): Boolean {
        if (!keyboardVisible) {
            visible = false; holding = false; sawRecording = false; quietSince = null; reservedAt = null
            return false
        }
        if (!visible || interaction) {
            if (!holding) sawRecording = false
            holding = true
            quietSince = null
            reservedAt = nowMs
        }
        visible = true
        if (externalRecording) {
            holding = true; sawRecording = true; quietSince = null
        } else if (holding && sawRecording) {
            val since = quietSince ?: nowMs.also { quietSince = it }
            if (nowMs - since >= quietMs) {
                holding = false; sawRecording = false; quietSince = null
                reservedAt = null
            }
        } else if (holding && reservedAt?.let { nowMs - it >= reservationMs } == true) {
            // A keyboard can stay open indefinitely after dictation, or never start a recorder.
            // Polling must release that unused reservation without requiring a window change.
            holding = false
            reservedAt = null
        }
        return holding
    }
}
