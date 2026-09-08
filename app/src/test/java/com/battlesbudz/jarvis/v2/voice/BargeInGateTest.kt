package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class BargeInGateTest {
    @Test fun speechWhileThinkingNeedsNoPlaybackProbe() {
        assertEquals(BargeInGate.Action.CONFIRM, BargeInGate().update(true, false, 0))
    }
    @Test fun speakerEchoThatDisappearsWhenPausedCannotInterrupt() {
        val gate = BargeInGate()
        assertEquals(BargeInGate.Action.PAUSE, gate.update(true, true, 0))
        assertEquals(BargeInGate.Action.WAIT, gate.update(true, false, 100))
        assertEquals(BargeInGate.Action.RESET_DETECTOR, gate.update(true, false, 200))
        assertEquals(BargeInGate.Action.WAIT, gate.update(false, false, 400))
        assertEquals(BargeInGate.Action.RESUME, gate.update(false, false, 700))
        assertFalse(gate.confirmed)
    }
    @Test fun speechSurvivingEchoDecayConfirmsAndDoesNotReprobe() {
        val gate = BargeInGate()
        gate.update(true, true, 0)
        gate.update(false, false, 200)
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(true, false, 400))
        assertEquals(BargeInGate.Action.CONFIRM, gate.update(false, false, 500))
    }
    @Test fun silenceNeverPausesOrInterruptsPlayback() {
        val gate = BargeInGate()
        repeat(100) { assertEquals(BargeInGate.Action.WAIT, gate.update(false, true, it * 100L)) }
    }
}
