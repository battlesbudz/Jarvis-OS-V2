package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptionSpeakerPolicyTest {
    @Test fun uncertainIsNotAnOwnerMatch() {
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(false, listOf(.99f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(true, emptyList()))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(true, listOf(.5f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.DIFFERENT, InterruptionSpeakerPolicy.decide(true, listOf(.1f, .2f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.MATCH, InterruptionSpeakerPolicy.decide(true, listOf(.8f)))
    }
}
