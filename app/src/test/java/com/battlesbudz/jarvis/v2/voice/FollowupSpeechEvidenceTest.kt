package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class FollowupSpeechEvidenceTest {
    @Test fun silentAndIsolatedSoundsDoNotQualify() {
        val e = FollowupSpeechEvidence()
        e.observe(32000, 0.01f, "Thank you", false); assertFalse(e.accepts())
        e.observe(3200, 0.9f, null, false); assertFalse(e.accepts())
    }
    @Test fun sustainedSpeechAllowsEmptyAsrFallback() {
        val e = FollowupSpeechEvidence()
        e.observe(8000, 0.8f, null, false); assertTrue(e.accepts())
        e.reset(); assertFalse(e.accepts())
    }
    @Test fun shortLiveCommandAndCorroboratedWhisperSurvive() {
        val e = FollowupSpeechEvidence()
        e.observe(3200, 0.9f, "No", false); assertTrue(e.accepts())
        e.reset(); e.observe(3200, 0.2f, "Please stop", true); assertTrue(e.accepts())
    }
}
