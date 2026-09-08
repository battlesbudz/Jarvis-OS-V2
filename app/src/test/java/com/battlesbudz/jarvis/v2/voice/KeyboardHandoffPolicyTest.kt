package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class KeyboardHandoffPolicyTest {
    @Test fun idleKeyboardReleasesWithoutOpeningAppOrNotificationTray() {
        val policy = KeyboardHandoffPolicy()
        assertTrue(policy.update(true, false, false, 0))
        assertTrue(policy.update(true, false, false, 2499))
        assertFalse(policy.update(true, false, false, 2500))
        repeat(120) { assertFalse(policy.update(true, false, false, 2750 + it * 250L)) }
        // A later tap gets a fresh, bounded chance to start dictation.
        assertTrue(policy.update(true, false, true, 40000))
        assertFalse(policy.update(true, false, false, 42500))
    }
    @Test fun activeDictationOutlivesReservationAndReleasesWithKeyboardStillVisible() {
        val policy = KeyboardHandoffPolicy()
        policy.update(true, false, false, 0)
        assertTrue(policy.update(true, true, false, 2000))
        assertTrue(policy.update(true, true, false, 60000))
        assertTrue(policy.update(true, false, false, 61000))
        assertTrue(policy.update(true, false, false, 61749))
        assertFalse(policy.update(true, false, false, 61750))
        assertFalse(policy.update(true, false, false, 70000))
    }
    @Test fun keyboardReappearingWithoutRecordingCannotLatchAnotherPause() {
        val policy = KeyboardHandoffPolicy()
        policy.update(true, true, false, 0)
        policy.update(true, false, false, 1000)
        assertFalse(policy.update(true, false, false, 1750))
        assertFalse(policy.update(false, false, false, 2000))
        assertTrue(policy.update(true, false, false, 3000))
        assertFalse(policy.update(true, false, false, 5500))
        // Recording starting after preflight expired still gets priority.
        assertTrue(policy.update(true, true, false, 6000))
        assertTrue(policy.update(true, false, false, 7000))
        assertFalse(policy.update(true, false, false, 7750))
    }
    @Test fun resumesWhenDictationEndsWithoutClosingKeyboardOrOpeningJarvis() {
        val policy = KeyboardHandoffPolicy()
        assertTrue(policy.update(true, false, false, 0))
        assertTrue(policy.update(true, true, false, 1000))
        assertTrue(policy.update(true, false, false, 5000))
        assertTrue(policy.update(true, false, false, 5749))
        assertFalse(policy.update(true, false, false, 5750))
        repeat(20) { assertFalse(policy.update(true, false, false, 6000 + it * 250L)) }
    }
    @Test fun nextKeyboardTapReservesAnotherDictationAndStopTapDoesNotLatchPause() {
        val policy = KeyboardHandoffPolicy()
        policy.update(true, true, false, 0)
        policy.update(true, false, false, 1000)
        assertFalse(policy.update(true, false, false, 1750))
        assertTrue(policy.update(true, false, true, 2000))
        assertTrue(policy.update(true, true, false, 2100))
        assertTrue(policy.update(true, false, true, 5000))
        assertFalse(policy.update(true, false, false, 5750))
    }
    @Test fun shortRecordingGapsDoNotLetJarvisStealDictation() {
        val policy = KeyboardHandoffPolicy()
        policy.update(true, true, false, 0)
        policy.update(true, false, false, 1000)
        assertTrue(policy.update(true, true, false, 1400))
        policy.update(true, false, false, 2000)
        assertTrue(policy.update(true, false, false, 2749))
        assertFalse(policy.update(true, false, false, 2750))
    }
    @Test fun closingKeyboardOrScreenOffClearsAnUnusedReservation() {
        val policy = KeyboardHandoffPolicy()
        assertTrue(policy.update(true, false, false, 0))
        assertFalse(policy.update(false, false, false, 1000))
        assertTrue(policy.update(true, false, false, 2000))
    }
}
