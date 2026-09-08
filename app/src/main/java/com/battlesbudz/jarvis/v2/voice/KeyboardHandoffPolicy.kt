package com.battlesbudz.jarvis.v2.voice

/** Main-thread state machine. Visible keyboard reserves the first attempt; recording end releases it. */
class KeyboardHandoffPolicy(private val quietMs: Long = 750) {
    private var visible = false
    private var holding = false
    private var sawRecording = false
    private var quietSince: Long? = null
    fun update(keyboardVisible: Boolean, externalRecording: Boolean, interaction: Boolean, nowMs: Long): Boolean {
        if (!keyboardVisible) {
            visible = false; holding = false; sawRecording = false; quietSince = null
            return false
        }
        if (!visible || interaction) {
            if (!holding) sawRecording = false
            holding = true
            quietSince = null
        }
        visible = true
        if (externalRecording) {
            holding = true; sawRecording = true; quietSince = null
        } else if (holding && sawRecording) {
            val since = quietSince ?: nowMs.also { quietSince = it }
            if (nowMs - since >= quietMs) {
                holding = false; sawRecording = false; quietSince = null
            }
        }
        return holding
    }
}
