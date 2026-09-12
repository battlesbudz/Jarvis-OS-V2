package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CallbackPcmParityTest {
    @Test fun unevenCallbacksMatchFullReturnedArray() {
        val samples = FloatArray(4099) { (it % 31 - 15) / 16f }
        val parity = CallbackPcmParity()
        samples.toList().chunked(317).forEach { parity.append(it.toFloatArray()) }
        val result = parity.finish(samples)
        assertTrue(result.matches)
        assertEquals(result.callbackHash, result.returnedHash)
        assertEquals(4099, result.frames.toInt())
    }

    @Test fun sameLengthCorruptionAndReorderedChunksAreDetected() {
        for (returned in listOf(floatArrayOf(0.1f, 0.3f), floatArrayOf(0.2f, 0.1f))) {
            val parity = CallbackPcmParity()
            parity.append(floatArrayOf(0.1f)); parity.append(floatArrayOf(0.2f))
            assertFalse(parity.finish(returned).matches)
        }
    }

    @Test fun missingAndDuplicateFramesAreDetected() {
        for (returned in listOf(floatArrayOf(0.1f), floatArrayOf(0.1f, 0.2f, 0.2f))) {
            val parity = CallbackPcmParity(); parity.append(floatArrayOf(0.1f, 0.2f))
            assertFalse(parity.finish(returned).matches)
        }
    }

    @Test fun finishedComparisonRejectsLateCallbacks() {
        val parity = CallbackPcmParity(); parity.append(floatArrayOf(0.5f)); parity.finish(floatArrayOf(0.5f))
        assertThrows(IllegalStateException::class.java) { parity.append(floatArrayOf(0.5f)) }
    }
}
