package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PaulStabilityTest {
    @Test fun resetModeChangesNativeSessionOnlyAtSubmissionBoundaries() {
        assertEquals("answer/segment-0", PocketSpeechPolicy.sessionId("answer", 0, true))
        assertNotEquals(PocketSpeechPolicy.sessionId("answer", 0, true), PocketSpeechPolicy.sessionId("answer", 1, true))
        assertEquals(PocketSpeechPolicy.sessionId("answer", 0, false), PocketSpeechPolicy.sessionId("answer", 1, false))
        assertEquals(". Ummm...", PocketSpeechPolicy.input("Ummm...", true))
        assertEquals("Ummm...", PocketSpeechPolicy.input("Ummm...", false))
    }

    @Test fun cushionAdaptsWithinBoundsAndZeroOptsOut() {
        val buffer = PaulPlaybackBuffer()
        assertEquals(200, buffer.target(200))
        buffer.observe(true)
        assertEquals(300, buffer.target(200))
        repeat(20) { buffer.observe(true) }
        assertEquals(600, buffer.target(200))
        assertEquals(600, buffer.target(400))
        assertEquals(0, buffer.target(0))
        repeat(20) { buffer.observe(false) }
        assertEquals(200, buffer.target(200))
    }

    @Test fun settingsHaveDistinctReproducibleIdentities() {
        val profiles = TtsBenchmarkProfile.selectableProfiles
        assertEquals(profiles.size, profiles.map { it.id }.toSet().size)
        assertTrue(profiles.any { it.nativeStreaming && !it.resetDecoder && !it.leadingPeriod && it.bufferMs == 0 })
        assertTrue(profiles.any { it.nativeStreaming && it.resetDecoder && it.leadingPeriod && it.bufferMs == 400 })
    }
}
