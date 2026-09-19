package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.MutableStateFlow

enum class VoicePhase(val label: String) {
    IDLE("Ready"), PREPARING("Preparing"), WAKE("Hey Jarvis"), WAKING("Waking up"),
    LISTENING("Listening"), THINKING("Thinking"), SPEAKING("Speaking"), PAUSED("Mic paused")
}
enum class VoiceControl { STOP_REPLY, END_CONVERSATION, PAUSE, RESUME }
class VoiceControlCancellation(val control: VoiceControl) : kotlinx.coroutines.CancellationException("Voice control: $control")

/** Runtime feedback survives navigation away from the voice screen. */
object VoiceSessionUi {
    val phase = MutableStateFlow(VoicePhase.IDLE)
    val status = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val armed = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val controls = kotlinx.coroutines.channels.Channel<VoiceControl>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun report(message: String) {
        status.value = message
        phase.value = when {
            message.startsWith("Jarvis session stopped") || message.contains("turn failed", true) -> VoicePhase.IDLE
            message.startsWith("Paused") -> VoicePhase.PAUSED
            message.startsWith("Waiting") -> VoicePhase.WAKE
            message.startsWith("Hey Jarvis detected") -> VoicePhase.WAKING
            message.startsWith("Voice Call is listening") -> VoicePhase.LISTENING
            message.contains("speaking", true) -> VoicePhase.SPEAKING
            message.startsWith("Processing") -> VoicePhase.THINKING
            else -> VoicePhase.PREPARING
        }
        if (phase.value != VoicePhase.LISTENING && phase.value != VoicePhase.WAKE) level.value = 0f
    }
}
