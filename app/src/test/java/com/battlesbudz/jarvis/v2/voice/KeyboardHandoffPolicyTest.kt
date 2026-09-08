package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class KeyboardHandoffPolicyTest {
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
