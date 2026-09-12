package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SourcePcmAnalysisTest {
    private fun analyze(pcm: ShortArray, chunks: Int, start: Long = 0): List<String> {
        val events = mutableListOf<String>()
        val analysis = SourcePcmAnalysis(24000, start, events::add)
        pcm.toList().chunked(chunks).forEach { analysis.append(it.toShortArray()) }
        analysis.finish(true)
        return events
    }

    @Test fun callbackBoundariesDoNotChangeWindowsOrPauseRanges() {
        val pcm = ShortArray(480) { 8192 } + ShortArray(720) + ShortArray(480) { 8192 }
        val expected = analyze(pcm, pcm.size, 124800)
        assertEquals(expected, analyze(pcm, 37, 124800))
        assertTrue(expected.first().contains("startFrame=125280 endFrame=126000 durationMs=30"))
        assertTrue(expected.last().contains("activeWindowFrames=960 nearSilentFrames=720"))
    }

    @Test fun levelsUseOnlyActiveWindowsAndCannotStandInForIntelligibility() {
        val summary = analyze(ShortArray(240) { 16384 } + ShortArray(240), 80).last()
        val db = Regex("activeWindowRmsDbfs=([^ ]+)").find(summary)!!.groupValues[1].toDouble()
        assertEquals(-6.0206, db, 0.0001)
        assertTrue(summary.contains("firstIntelligibleWordMs=unavailable"))
        assertTrue(summary.contains("qualityCause=not_determined"))
    }

    @Test fun trailingPartialAndClippingAreAccountedFor() {
        val summary = analyze(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, (-32767).toShort()), 1).last()
        assertTrue(summary.contains("endFrame=3"))
        assertTrue(summary.contains("railPcm16Samples=3"))
        assertTrue(summary.contains("activeWindowFrames=3"))
    }

    @Test fun silenceOnlyAndEmptyHaveUnavailableActiveLevel() {
        for (pcm in listOf(ShortArray(240), ShortArray(0))) {
            assertTrue(analyze(pcm, 240).last().contains("activeWindowRmsDbfs=unavailable"))
        }
    }

    @Test fun intervalOutputIsBoundedButTotalsAreComplete() {
        val pcm = (0 until 300).flatMap { i -> List(240) { if (i % 2 == 0) 0.toShort() else 1000.toShort() } }.toShortArray()
        val events = analyze(pcm, 503)
        assertEquals(129, events.size)
        assertTrue(events.last().contains("nearSilentIntervals=150 omittedIntervals=22"))
    }

    @Test fun unfinishedSubmissionIsExplicitAndFinishIsIdempotent() {
        val events = mutableListOf<String>()
        val a = SourcePcmAnalysis(24000, 0, events::add)
        a.append(ShortArray(20)); a.finish(false); a.finish(true)
        assertEquals(2, events.size)
        assertTrue(events.last().contains("complete=false"))
        assertThrows(IllegalStateException::class.java) { a.append(shortArrayOf(1)) }
    }

    @Test fun submissionBoundariesSplitWindowsAndPreserveGlobalOffsets() {
        val events = mutableListOf<String>()
        val trace = PocketStreamDiagnostics("test", events::add, analyzeSource = true)
        trace.begin(0, "A.", 24000); trace.chunk(0, ShortArray(100)); trace.finish(0)
        trace.begin(1, "B.", 24000); trace.chunk(1, ShortArray(100)); trace.summary(0, 0, false)
        assertTrue(events.any { it.contains("index=1 event=near_silence startFrame=100 endFrame=200") })
        assertTrue(events.any { it.contains("index=1 event=source_summary") && it.contains("complete=false") })
    }
}
