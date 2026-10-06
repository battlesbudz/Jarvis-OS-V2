package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class LiveCallAudioEvidenceTest {
    @Test fun everyCallIsCapturedWithoutArming() {
        LiveCallAudioEvidence.begin("call-a")
        LiveCallAudioEvidence.record("microphone", byteArrayOf(1, 2, 3, 4), 16000, 42)
        LiveCallAudioEvidence.finish()
        val result = LiveCallAudioEvidence.snapshot()!!
        assertEquals("call-a", result.call)
        assertEquals(48, result.files.getValue("microphone.wav").size)
        assertTrue(result.report.contains("atMs=42"))
        LiveCallAudioEvidence.begin("call-b")
        assertEquals("call-b", LiveCallAudioEvidence.snapshot()!!.call)
    }
    @Test fun rollingAudioAndEventsStayBoundedWithHonestOffsets() {
        LiveCallAudioEvidence.begin("call")
        repeat(300) { LiveCallAudioEvidence.record("microphone", ByteArray(3200), 16000, it.toLong()) }
        repeat(2000) { LiveCallAudioEvidence.event("test=$it") }
        val result = LiveCallAudioEvidence.snapshot()!!
        assertEquals(800044, result.files.getValue("microphone.wav").size)
        assertTrue(result.report.contains("startFrame=80000"))
        assertTrue(result.report.contains("truncated=true"))
        assertFalse(result.report.contains("test=0\n"))
    }
    @Test fun sourceReferencesPreserveSignedPcmAndSnapshotIsImmutable() {
        LiveCallAudioEvidence.begin("call")
        val stream = LiveCallAudioEvidence.newStream("answer")
        LiveCallAudioEvidence.recordOutput(stream, shortArrayOf(-1, 32767, -32768), 0, 3, 22050)
        val snapshot = LiveCallAudioEvidence.snapshot()!!
        assertArrayEquals(byteArrayOf(-1, -1, -1, 127, 0, -128), snapshot.files.getValue("$stream.wav").drop(44).toByteArray())
        LiveCallAudioEvidence.begin("next")
        assertEquals(50, snapshot.files.getValue("$stream.wav").size)
        assertTrue(snapshot.report.contains("output=written_reference_not_acoustic_recording"))
    }
}
