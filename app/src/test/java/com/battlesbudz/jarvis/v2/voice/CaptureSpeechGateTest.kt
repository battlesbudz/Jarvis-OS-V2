package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class CaptureSpeechGateTest {
    private fun calibrated(rms: Double) = CaptureSpeechGate().also { gate ->
        repeat(3) { gate.accept(SpeechDecision(false, .01f), rms, it * 100L) }
    }
    @Test fun weakVadAtRoomFloorIsNotSpeech() {
        val gate = calibrated(29.0)
        assertEquals(SpeechDecision(false, 0f), gate.accept(SpeechDecision(true, .525f), 44.0, 300))
        assertEquals(29.0, gate.noiseFloorRms, .001)
    }
    @Test fun quietSpeechAboveFloorAndStrongWhisperArePreserved() {
        val gate = calibrated(29.0)
        val weak = SpeechDecision(false, .23f)
        assertEquals(weak, gate.accept(weak, 70.0, 300))
        val strong = SpeechDecision(true, .95f)
        assertEquals(strong, gate.accept(strong, 20.0, 400))
    }
    @Test fun singleLoudCalibrationFrameCannotSuppressOpeningSpeech() {
        val gate = CaptureSpeechGate()
        gate.accept(SpeechDecision(false, .01f), 1694.0, 0)
        val speech = SpeechDecision(true, .6f)
        assertEquals(speech, gate.accept(speech, 400.0, 100))
    }
    @Test fun louderSpeechCannotRaiseTheNoiseFloor() {
        val gate = calibrated(30.0)
        repeat(20) { gate.accept(SpeechDecision(true, .99f), 2000.0, 300L + it * 100) }
        assertEquals(30.0, gate.noiseFloorRms, .001)
        assertTrue(gate.accept(SpeechDecision(true, .6f), 70.0, 2400).isSpeech)
    }
    @Test fun staleHighFloorExpiresEvenDuringContinuousSpeech() {
        val gate = calibrated(1694.0)
        repeat(40) { gate.accept(SpeechDecision(true, .99f), 2000.0, 300L + it * 100) }
        assertEquals(0.0, gate.noiseFloorRms, .001)
        assertTrue(gate.accept(SpeechDecision(true, .79f), 491.0, 4400).isSpeech)
        repeat(3) { gate.accept(SpeechDecision(false, .01f), 30.0, 4500L + it * 100) }
        assertFalse(gate.accept(SpeechDecision(true, .525f), 44.0, 4800).isSpeech)
    }
}
