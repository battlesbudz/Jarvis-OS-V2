package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInterruptionPolicyTest {
    @Test fun idlePhoneNeverReservesTheMicrophone() {
        val policy = MicrophoneInterruptionPolicy()
        repeat(120) { assertFalse(policy.update(false, it * 500L)) }
    }
    @Test fun longExternalRecordingCannotExpireTheHold() {
        val policy = MicrophoneInterruptionPolicy()
        assertTrue(policy.update(true, 0))
        assertTrue(policy.update(true, 120000))
        assertTrue(policy.update(false, 120500))
        assertTrue(policy.update(false, 121249))
        assertFalse(policy.update(false, 121250))
        assertFalse(policy.update(false, 122000))
    }
    @Test fun recorderSwitchesAndShortGapsDoNotRearmEarly() {
        val policy = MicrophoneInterruptionPolicy()
        policy.update(true, 0)
        assertTrue(policy.update(false, 1000))
        assertTrue(policy.update(true, 1500))
        assertTrue(policy.update(false, 2000))
        assertTrue(policy.update(false, 2749))
        assertFalse(policy.update(false, 2750))
        assertTrue(policy.update(true, 3000))
        assertTrue(policy.update(false, 5000))
        assertFalse(policy.update(false, 5750))
    }
}
