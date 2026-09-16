package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CaptureSpeechGateTest {
    @Test fun weakVadAtRoomFloorIsNotSpeech() {
        val gate = CaptureSpeechGate()
        gate.accept(SpeechDecision(false, .01f), 29.0)
        assertEquals(SpeechDecision(false, 0f), gate.accept(SpeechDecision(true, .525f), 44.0))
        assertEquals(29.0, gate.noiseFloorRms, .001)
    }
    @Test fun quietSpeechAboveFloorAndStrongWhisperArePreserved() {
        val gate = CaptureSpeechGate()
        gate.accept(SpeechDecision(false, .01f), 29.0)
        val weak = SpeechDecision(false, .23f)
        assertEquals(weak, gate.accept(weak, 70.0))
        val strong = SpeechDecision(true, .95f)
        assertEquals(strong, gate.accept(strong, 20.0))
    }
    @Test fun noCalibrationAndImmediateOpeningSpeechArePreserved() {
        val gate = CaptureSpeechGate()
        val speech = SpeechDecision(true, .6f)
        assertEquals(speech, gate.accept(speech, 20.0))
    }
    @Test fun louderSpeechCannotRaiseTheNoiseFloor() {
        val gate = CaptureSpeechGate()
        repeat(5) { gate.accept(SpeechDecision(false, .01f), 30.0) }
        repeat(100) { gate.accept(SpeechDecision(true, .99f), 2000.0) }
        assertEquals(30.0, gate.noiseFloorRms, .001)
        assertTrue(gate.accept(SpeechDecision(true, .6f), 70.0).isSpeech)
    }
}
