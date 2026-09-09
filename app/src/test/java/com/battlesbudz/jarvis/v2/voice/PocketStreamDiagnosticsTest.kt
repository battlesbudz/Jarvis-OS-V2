package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PocketStreamDiagnosticsTest {
    @Test fun distinguishesDeliveryChunksFromNewTextSubmissions() {
        val events = mutableListOf<String>()
        val trace = PocketStreamDiagnostics("answer-1", events::add)
        trace.begin(0, "Hello.", 24000)
        trace.chunk(0, shortArrayOf(10, 20))
        trace.chunk(0, shortArrayOf(25, 30))
        trace.finish(0)
        trace.begin(1, "Next sentence.", 24000)
        trace.chunk(1, shortArrayOf(100, 110))
        trace.finish(1)
        trace.summary(1, 52, true)
        assertTrue(events.last().contains("submissions=2 callbacks=3 frames=6"))
        assertTrue(events.last().contains("maxWithinCallJoinDeltaPcm16=5 maxAcrossCallJoinDeltaPcm16=70"))
        assertTrue(events.last().contains("underruns=1 estimatedSupplyGapMs=52"))
        assertTrue(events.any { it.contains("index=1") && it.contains("startFrame=4") })
        assertTrue(events.first().contains("nativeResetObserved=unavailable"))
    }

    @Test fun repeatedTextIsEvidenceNotAnAutomaticRegenerationVerdict() {
        val events = mutableListOf<String>()
        val trace = PocketStreamDiagnostics("answer-2", events::add)
        trace.begin(0, "Yes.", 24000)
        trace.chunk(0, shortArrayOf())
        trace.begin(1, "Yes.", 24000)
        trace.summary(0, 0, false)
        assertTrue(events[1].contains("repeatedText=true"))
        assertTrue(events.last().contains("callbacks=0 frames=0 repeatedTextCalls=1"))
        assertTrue(events.last().contains("qualityCause=not_determined"))
    }
}
