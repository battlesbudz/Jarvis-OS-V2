package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechCaptureProfileTest {
    @Test fun captureIsHardwiredToCallNoiseReduction() {
        val profile = SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED
        assertEquals("communication_noise_filtered", profile.id)
        assertEquals("Call noise reduction", profile.label)
        assertTrue(profile.communicationInput)
        assertTrue(profile.noiseSuppression)
    }
    @Test fun noSpeechPreservingProfileRemains() {
        assertEquals(1, SpeechCaptureProfile.entries.size)
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.entries.single())
    }
}
