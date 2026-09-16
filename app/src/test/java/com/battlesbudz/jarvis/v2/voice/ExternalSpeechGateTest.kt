package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class ExternalSpeechGateTest {
    @Test fun silenceDoesNotReachNativeDecoderAndOnsetIsRetained() {
        val gate = ExternalSpeechGate()
        gate.observe(false)
        repeat(20) { assertEquals(0, gate.accept(ByteArray(3200)).size) }
        gate.observe(true)
        assertEquals(7680 + 3200, gate.accept(ByteArray(3200)).size)
        gate.observe(false)
        repeat(3) { assertEquals(3200, gate.accept(ByteArray(3200)).size) }
        assertEquals(640, gate.accept(ByteArray(3200)).size)
        repeat(30) { assertEquals(0, gate.accept(ByteArray(3200)).size) }
        gate.observe(true)
        assertEquals(7680 + 3200, gate.accept(ByteArray(3200)).size)
        assertEquals(3200, gate.accept(ByteArray(3200)).size)
    }
    @Test fun boundedRecordingAndQualifiedProbeDoNotNeedASecondVadGate() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, ExternalSpeechGate().accept(pcm))
        val probe = ExternalSpeechGate(); probe.observe(true)
        assertArrayEquals(pcm, probe.accept(pcm))
    }
    @Test fun shortInternalPausesAndTrailingConsonantsRemainByteExact() {
        val gate = ExternalSpeechGate()
        gate.observe(true)
        val speech = ByteArray(3200) { 1 }
        assertArrayEquals(speech, gate.accept(speech))
        gate.observe(false)
        val pause = ByteArray(6400) { 2 }
        assertArrayEquals(pause, gate.accept(pause))
        gate.observe(true)
        assertArrayEquals(speech, gate.accept(speech))
        assertEquals(12800L, gate.acceptedBytes)
    }
}
