package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class GemmaLatencyCasesTest {
    private fun sample(audio: ByteArray = ByteArray(100)) = requireNotNull(
        GemmaLatencySample.capture("Why is the sky blue?", "History and current request.", audio, "turn", 42))

    @Test fun captureOwnsAudioRatherThanRetainingMutableCaptureBuffer() {
        val source = ByteArray(100) { 7 }
        val captured = sample(source)
        source.fill(9)
        assertEquals(7, captured.audio[0].toInt())
        assertEquals("turn", captured.sourceTurn)
        assertEquals(42L, captured.capturedAtMs)
    }

    @Test fun rejectsUnboundedOrEmptyInputWithoutTruncatingPairedAudio() {
        assertNull(GemmaLatencySample.capture("", "context", ByteArray(100), "t", 0))
        assertNull(GemmaLatencySample.capture("x".repeat(4001), "context", ByteArray(100), "t", 0))
        assertNull(GemmaLatencySample.capture("question", "x".repeat(32001), ByteArray(100), "t", 0))
        assertNull(GemmaLatencySample.capture("question", "context", ByteArray(43), "t", 0))
        assertNull(GemmaLatencySample.capture("question", "context", ByteArray(1_048_577), "t", 0))
    }

    @Test fun audioComparisonChangesOnlyAudioInput() {
        val sample = sample()
        val cases = GemmaLatencyCases.cases(sample, 1)
        val text = cases.single { it.mode == GemmaLatencyCases.Mode.CONTEXT_TEXT }
        val audio = cases.single { it.mode == GemmaLatencyCases.Mode.CONTEXT_AUDIO }
        assertEquals(text.prompt, audio.prompt)
        assertNull(text.audio)
        assertArrayEquals(sample.audio, audio.audio)
        assertTrue(cases.single { it.mode == GemmaLatencyCases.Mode.SHORT_TEXT }.prompt.contains(sample.transcript))
        assertNull(cases.single { it.mode == GemmaLatencyCases.Mode.SHORT_TEXT }.audio)
    }

    @Test fun everyModeOccupiesEveryPositionOnceAcrossThreePasses() {
        val passes = (1..3).map { GemmaLatencyCases.cases(sample(), it) }
        val all = GemmaLatencyCases.Mode.entries.toSet()
        passes.forEach { assertEquals(all, it.map { c -> c.mode }.toSet()) }
        for (position in 0..2) assertEquals(all, passes.map { it[position].mode }.toSet())
    }
}
