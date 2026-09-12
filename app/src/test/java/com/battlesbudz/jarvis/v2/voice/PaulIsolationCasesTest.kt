package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PaulIsolationCasesTest {
    @Test fun pairedCasesHoldTextAndGroupingConstantForEachComparison() {
        for (prefix in listOf("opening", "paragraph")) {
            val cases = PaulIsolationCases.all.associateBy { it.id }
            val one = cases.getValue("$prefix-one-reset")
            val grouped = cases.getValue("$prefix-grouped-reset")
            val retained = cases.getValue("$prefix-grouped-retained")
            assertEquals(one.text, grouped.text)
            assertEquals(grouped.submissions, retained.submissions)
            assertEquals(1, one.submissions.size)
            assertEquals(2, grouped.submissions.size)
            assertTrue(one.reset && grouped.reset)
            assertFalse(retained.reset)
        }
    }

    @Test fun truncatedOpeningRegressionIsIsolatedAndOriginalGroupedTextIsPreserved() {
        val cases = PaulIsolationCases.all.associateBy { it.id }
        assertEquals(listOf("I understand."), cases.getValue("standalone-reset").submissions)
        assertEquals(TtsBenchmarkSamples.all.getValue("short-opening-v2"), cases.getValue("opening-grouped-reset").text)
        assertEquals(7, cases.size)
    }
}
