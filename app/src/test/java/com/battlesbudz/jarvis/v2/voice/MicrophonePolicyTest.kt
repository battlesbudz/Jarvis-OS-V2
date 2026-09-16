package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophonePolicyTest {
    @Test fun waitsUntilOtherRecorderReleasesBeforeStarting() {
        assertTrue(MicrophonePolicy.shouldYield(1, false, false, false))
        assertFalse(MicrophonePolicy.shouldYield(0, false, false, false))
    }
    @Test fun ownRecorderDoesNotCauseAnEndlessPause() {
        assertFalse(MicrophonePolicy.shouldYield(1, true, false, false))
        assertTrue(MicrophonePolicy.shouldYield(2, true, false, false))
    }
    @Test fun callsAndAndroidSilencingAlwaysWin() {
        assertTrue(MicrophonePolicy.shouldYield(1, true, true, false))
        assertTrue(MicrophonePolicy.shouldYield(0, false, false, true))
    }
}
