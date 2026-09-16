package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class KokoroPcmDeliveryTest {
    @Test fun sentencePcmIsDeliveredExactlyOnceWithBoundedEntriesAndOneCaptionStart() {
        val pcm = ShortArray(24123) { (it % 32767).toShort() }
        val delivered = mutableListOf<Short>()
        var starts = 0
        KokoroPcmDelivery.forEachChunk(pcm, 24000) { part, first ->
            assertTrue(part.size in 1..9600)
            if (first) starts++
            delivered.addAll(part.toList())
        }
        assertArrayEquals(pcm, delivered.toShortArray())
        assertEquals(1, starts)
    }

    @Test fun cancelledConsumerDoesNotDeliverAnyLaterChunks() {
        var calls = 0
        val cancelled = IllegalStateException("consumer closed")
        try {
            KokoroPcmDelivery.forEachChunk(ShortArray(48000), 24000) { _, _ ->
                calls++
                throw cancelled
            }
            fail("must propagate closure")
        } catch (error: IllegalStateException) { assertSame(cancelled, error) }
        assertEquals(1, calls)
    }

    @Test fun recoveryRequiresSentencePunctuation() {
        for (text in listOf("Here is why,", "a long unfinished clause", "", "Next:"))
            assertFalse(KokoroPcmDelivery.endsSentence(text))
        for (text in listOf("It is blue.", "Ready?", "He said ‘yes!’  "))
            assertTrue(KokoroPcmDelivery.endsSentence(text))
    }
}
