package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test fun fastSynthesisAddsNoStartupWait() {
        assertEquals(0L, PlaybackBufferPolicy.startupWaitMs(400, 2000))
    }
    @Test fun marginalSynthesisGetsSomeHeadroom() {
        assertEquals(400L, PlaybackBufferPolicy.startupWaitMs(2000, 2000))
    }
    @Test fun slowSynthesisCannotCauseAnUnboundedStartupWait() {
        assertEquals(1200L, PlaybackBufferPolicy.startupWaitMs(19200, 11100))
        assertEquals(0L, PlaybackBufferPolicy.startupWaitMs(500, 0))
    }
}
