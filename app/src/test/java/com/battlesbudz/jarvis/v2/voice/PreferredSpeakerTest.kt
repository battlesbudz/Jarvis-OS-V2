package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PreferredSpeakerTest {
    private val owner = floatArrayOf(1f, 0f, 0f)
    private val tv = floatArrayOf(0f, 1f, 0f)
    @Test fun repeatedTurnsWithinOneActivationCannotEnrollTelevision() {
        val s = PreferredSpeaker()
        repeat(100) { s.observe(tv, 90f, "one-activation") }
        assertNull(s.preferred)
        assertEquals(4f, s.candidates.single().weight)
    }
    @Test fun consistentIndependentActivationsLearnPreference() {
        val s = PreferredSpeaker()
        repeat(3) { s.observe(owner, 3f, "wake-$it") }
        assertNotNull(s.preferred)
        assertEquals(1f, s.score(owner)!!, 0.001f)
        assertEquals(0f, s.score(tv)!!, 0.001f)
    }
    @Test fun competingSpeakersRemainUncertain() {
        val s = PreferredSpeaker()
        repeat(3) { s.observe(owner, 3f, "owner-$it"); s.observe(tv, 3f, "tv-$it") }
        assertNull(s.preferred)
    }
    @Test fun backgroundCannotReplaceEstablishedPreference() {
        val s = PreferredSpeaker()
        repeat(3) { s.observe(owner, 3f, "owner-$it") }
        repeat(100) { s.observe(tv, 30f, "tv-$it") }
        assertEquals(1f, s.score(owner)!!, 0.001f)
    }
    @Test fun silenceShortAndInvalidVectorsDoNotTrain() {
        val s = PreferredSpeaker()
        s.observe(owner, 0.5f, "short")
        s.observe(floatArrayOf(Float.NaN), 4f, "invalid")
        s.observe(floatArrayOf(0f, 0f), 4f, "silence")
        assertTrue(s.candidates.isEmpty())
    }
}
