package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeakerTimelineTest {
    @Test fun lateCandidateUsesLatePassageAudioNotItsOpening() {
        val timeline = PlaybackSpeakerTimeline()
        val pcm = ShortArray(16000 * 20) { if (it < 16000 * 3) 8192 else -16384 }
        timeline.append(0, pcm, 16000)
        timeline.observe(1000, 16000)
        timeline.observe(12000, 16000 * 12L)
        val reference = timeline.reference(12040, 600)!!
        assertTrue(reference.windows.flatMap { it.asIterable() }.all { it == -.5f })
        assertTrue(reference.description.contains("endFrame=192000"))
        assertTrue(reference.description.contains("capturePositionAgeMs=40"))
    }
    @Test fun delayedRecognitionUsesCapturePositionNotPositionWhenCheckFinishes() {
        val timeline = PlaybackSpeakerTimeline()
        timeline.append(0, ShortArray(16000 * 20) { if (it < 16000 * 13) 8192 else -16384 }, 16000)
        timeline.observe(12000, 192000)
        timeline.observe(14000, 224000)
        assertTrue(timeline.reference(12050, 600)!!.windows.flatMap { it.asIterable() }.all { it == .25f })
    }
    @Test fun windowCanCrossPassageBoundaryWithoutUsingUnplayedFutureSamples() {
        val timeline = PlaybackSpeakerTimeline()
        timeline.append(0, ShortArray(16000) { 8192 }, 16000)
        timeline.append(16000, ShortArray(16000) { -16384 }, 16000)
        timeline.observe(1200, 19200)
        val values = timeline.reference(1200, 600)!!.windows.flatMap { it.asIterable() }
        assertEquals(9600, values.count { it == .25f })
        assertEquals(3200, values.count { it == -.5f })
    }
    @Test fun absentStaleOrMissingPlaybackFailsClosedInsteadOfReusingOpening() {
        val timeline = PlaybackSpeakerTimeline()
        assertNull(timeline.reference(0, 600))
        timeline.append(0, ShortArray(16000) { 8192 }, 16000)
        assertTrue(timeline.reference(1000, 600)!!.windows.isEmpty())
        timeline.observe(1000, 16000)
        assertTrue(timeline.reference(1400, 600)!!.windows.isEmpty())
        assertTrue(timeline.reference(900, 600)!!.windows.isEmpty())
        assertTrue(timeline.reference(1000, 600)!!.windows.isNotEmpty())
    }
    @Test fun clockRegressionCannotReplaceAnObservedPlaybackPosition() {
        val timeline = PlaybackSpeakerTimeline()
        timeline.append(0, ShortArray(32000) { 8192 }, 16000)
        timeline.observe(1000, 16000)
        timeline.observe(900, 20000)
        timeline.observe(1100, 15000)
        assertTrue(timeline.reference(1150, 600)!!.description.contains("endFrame=16000"))
    }
}
