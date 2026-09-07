package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCallPolicyTest {
    @Test fun explicitWholeUtteranceGoodbyeEndsCall() {
        for (text in listOf("Goodbye!", "Goodbye, Jarvis.", "Stop listening", "Jarvis, stop listening.")) {
            assertTrue(text, VoiceCallPolicy.isGoodbye(text))
        }
    }
    @Test fun MentioningGoodbyeOrQuotingItInARequestDoesNotHangUp() {
        for (text in listOf("Tell me a story about saying goodbye", "Don't stop listening", "What does goodbye mean?", "")) {
            assertFalse(text, VoiceCallPolicy.isGoodbye(text))
        }
    }
    @Test fun turnSilenceAndCallInactivityAreSeparate() {
        assertEquals(3000L, VoiceCallPolicy.TURN_SILENCE_MS)
        assertEquals(20000L, VoiceCallPolicy.CALL_INACTIVITY_MS)
    }
}
