package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class BargeInGateTest {
    @Test fun speechWhileThinkingConfirmsWithoutWaitingForWords() {
        assertEquals(BargeInGate.Action.CONFIRM, BargeInGate().update(true, false, 0))
    }
    @Test fun repeatedSpeakerEchoNeverInterruptsOrRequestsAPause() {
        val gate = BargeInGate()
        repeat(100) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(true, true, it * 100L, "The garden gate is open", "The garden gate is open, and the breeze is cool.")) }
        assertFalse(gate.confirmed)
    }
    @Test fun stableDifferentWordsConfirmExactlyOnce() {
        val gate = BargeInGate()
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 0, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 199, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(false, true, 200, "open youtube", "The sky is blue"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(false, true, 201))
    }
    @Test fun changingWordsRestartConfirmation() {
        val gate = BargeInGate()
        gate.update(true, true, 0, "open instagram", "Hello there")
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, true, 200, "open youtube", "Hello there"))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, true, 400, "open youtube", "Hello there"))
    }
    @Test fun hallucinatedWordsWithoutAcousticSpeechCannotInterrupt() {
        val gate = BargeInGate()
        repeat(20) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(false, true, it * 100L, "open youtube", "The sky is blue")) }
    }
    @Test fun stopCanInterruptButJarvisSayingStopCannot() {
        val gate = BargeInGate()
        gate.update(true, true, 0, "stop", "The sky is blue")
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, true, 200, "stop", "The sky is blue"))
        val echo = BargeInGate()
        repeat(10) { assertEquals(BargeInGate.Action.WAIT,
            echo.update(true, true, it * 100L, "stop", "You can say stop to interrupt me")) }
    }
    @Test fun isolatedRecognitionErrorInEchoIsRejected() {
        val gate = BargeInGate()
        repeat(10) { assertEquals(BargeInGate.Action.WAIT,
            gate.update(true, true, it * 100L, "The garden grate is open", "The garden gate is open")) }
    }
}
