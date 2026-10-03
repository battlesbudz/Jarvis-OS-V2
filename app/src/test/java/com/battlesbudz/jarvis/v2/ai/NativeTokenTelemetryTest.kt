package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class NativeTokenTelemetryTest {
    @Test fun nativeCountsAndRatesKeepTheirOwnSdkClock() {
        val value = NativeTokenTelemetry.checked(41, 12, .25, 120.0, 18.0, .3)!!
        assertEquals(41, value.inputTokens)
        assertEquals(12, value.outputTokens)
        assertEquals(250.0, value.timeToFirstTokenMs!!, 0.001)
        assertEquals(18.0, value.decodeTokensPerSecond!!, 0.001)
    }
    @Test fun missingOrInvalidNativeValuesNeverBecomeEstimates() {
        assertNull(NativeTokenTelemetry.checked(-1, 12, .25, 120.0, 18.0, .3))
        val empty = NativeTokenTelemetry.checked(0, 0, 0.0, Double.NaN, 0.0, .1)!!
        assertNull(empty.timeToFirstTokenMs)
        assertNull(empty.prefillTokensPerSecond)
        assertNull(empty.decodeTokensPerSecond)
        assertNull(NativeTokenTelemetry.checked(4, 2, .1, 5.0, 4.0, Double.NaN))
    }
}
