package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class ExternalSpeechGateTest {
    @Test fun silenceDoesNotReachNativeDecoderAndOnsetIsRetained() {
        val gate = ExternalSpeechGate()
        gate.observe(false)
        repeat(20) { assertEquals(0, gate.accept(ByteArray(3200) { it.toByte() }).size) }
        gate.observe(true)
        assertEquals(38400 + 3200, gate.accept(ByteArray(3200)).size)
        gate.observe(false)
        assertEquals(3200, gate.accept(ByteArray(3200)).size) // retain pauses until outer endpoint
        assertEquals(3200, gate.accept(ByteArray(3200)).size) // no duplicate pre-roll
    }
    @Test fun boundedRecordingAndQualifiedProbeDoNotNeedASecondVadGate() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, ExternalSpeechGate().accept(pcm))
        val probe = ExternalSpeechGate(); probe.observe(true)
        assertArrayEquals(pcm, probe.accept(pcm))
    }
}
