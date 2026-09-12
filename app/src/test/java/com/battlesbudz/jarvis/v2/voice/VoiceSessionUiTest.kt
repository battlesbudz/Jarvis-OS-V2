package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionUiTest {
    @Test fun waitingAndMicHandoffCannotAppearAsThinking() {
        VoiceSessionUi.report("Waiting for Hey Jarvis — microphone active")
        assertEquals(VoicePhase.WAKE, VoiceSessionUi.phase.value)
        VoiceSessionUi.level.value = 0.8f
        VoiceSessionUi.report("Paused — microphone in use by another app")
        assertEquals(VoicePhase.PAUSED, VoiceSessionUi.phase.value)
        assertEquals(0f, VoiceSessionUi.level.value)
        VoiceSessionUi.report("Voice Call is listening — speak now.")
        assertEquals(VoicePhase.LISTENING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Processing your Voice Call turn locally…")
        assertEquals(VoicePhase.THINKING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Jarvis is speaking…")
        assertEquals(VoicePhase.SPEAKING, VoiceSessionUi.phase.value)
        VoiceSessionUi.report("Jarvis session stopped — microphone off.")
        assertEquals(VoicePhase.IDLE, VoiceSessionUi.phase.value)
    }
}
