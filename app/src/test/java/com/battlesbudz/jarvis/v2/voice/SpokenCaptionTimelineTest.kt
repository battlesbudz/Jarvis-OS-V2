package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenCaptionTimelineTest {
    @Test fun waitsForPlaybackAndDoesNotRevealQueuedAnswer() {
        val t = SpokenCaptionTimeline()
        t.append(0, 1000, ShortArray(4000) { 1000 }, "One two three four five six seven eight")
        t.append(4000, 1000, ShortArray(2000), "Next phrase is hidden")
        assertEquals("", t.at(0).caption)
        assertFalse(t.at(100).caption.contains("eight"))
        assertFalse(t.at(3900).caption.contains("Next"))
        assertEquals(t.at(1200), t.at(1200)) // a stalled playback clock cannot advance captions
        assertTrue(t.at(4100).caption.contains("Next"))
    }
    @Test fun retainsBoundedContextAndFinishesWithZeroEnergy() {
        val t = SpokenCaptionTimeline()
        repeat(10) { i ->
            t.append(i * 1000L, 1000, ShortArray(1000) { 5000 }, "one two three four five six")
            t.at((i + 1) * 1000L)
        }
        val last = t.at(10000)
        assertEquals(32, last.caption.split(" ").size)
        assertEquals(0f, last.level)
    }
}
