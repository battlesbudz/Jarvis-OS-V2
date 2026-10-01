package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechCaptureProfileTest {
    @Test fun newAndUnknownPreferencesPreserveSpeechRatherThanSelectingVoipProcessing() {
        for (id in listOf(null, "", "missing_profile")) {
            val profile = SpeechCaptureProfile.fromId(id)
            assertEquals(SpeechCaptureProfile.SPEECH_PRESERVING, profile)
            assertFalse(profile.communicationInput)
            assertFalse(profile.noiseSuppression)
        }
    }
    @Test fun explicitCommunicationFallbackRestoresPriorSourceAndNoiseRequest() {
        val profile = SpeechCaptureProfile.fromId("communication_noise_filtered")
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, profile)
        assertTrue(profile.communicationInput)
        assertTrue(profile.noiseSuppression)
    }
    @Test fun eachProfileRoundTripsItsPersistedIdentity() {
        assertEquals(SpeechCaptureProfile.entries.size, SpeechCaptureProfile.entries.map { it.id }.distinct().size)
        SpeechCaptureProfile.entries.forEach { assertEquals(it, SpeechCaptureProfile.fromId(it.id)) }
    }
}
