package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class InterruptionSpeakerPolicyTest {
    @Test fun strongOwnerSimilarityCannotOverridePlaybackVoiceSimilarity() {
        assertEquals(InterruptionSpeakerPolicy.Decision.PLAYBACK_ECHO,
            InterruptionSpeakerPolicy.decide(true, listOf(.85f), listOf(.9f), true))
        assertEquals(InterruptionSpeakerPolicy.Decision.PLAYBACK_ECHO,
            InterruptionSpeakerPolicy.decide(true, listOf(.85f, .83f), listOf(.6f, .82f), true))
        assertEquals(InterruptionSpeakerPolicy.Decision.MATCH,
            InterruptionSpeakerPolicy.decide(true, listOf(.85f), listOf(.6f), true))
    }
    @Test fun missingPlaybackEmbeddingFailsClosedAndEveryWindowMustMatchOwner() {
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN,
            InterruptionSpeakerPolicy.decide(true, listOf(.9f), emptyList(), true))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN,
            InterruptionSpeakerPolicy.decide(true, listOf(.9f), listOf(Float.NaN), true))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN,
            InterruptionSpeakerPolicy.decide(true, listOf(.9f, .4f)))
    }
    @Test fun uncertainIsNotAnOwnerMatch() {
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(false, listOf(.99f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(true, emptyList()))
        assertEquals(InterruptionSpeakerPolicy.Decision.UNCERTAIN, InterruptionSpeakerPolicy.decide(true, listOf(.5f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.DIFFERENT, InterruptionSpeakerPolicy.decide(true, listOf(.1f, .2f)))
        assertEquals(InterruptionSpeakerPolicy.Decision.MATCH, InterruptionSpeakerPolicy.decide(true, listOf(.8f)))
    }
}
