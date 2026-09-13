package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceLoadFixturesTest {
    @Test fun capturedCallbacksAppearExactlyOnceAndDoNotAliasNativeMemory() {
        val buffer = VoiceLoadPcm(6)
        val callback = shortArrayOf(1, 2, 3)
        buffer.append(callback, 24000)
        callback[0] = 99
        buffer.append(shortArrayOf(4, 5), 24000)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), buffer.snapshot())
        assertEquals(5, buffer.frames)
        assertThrows(IllegalStateException::class.java) { buffer.append(shortArrayOf(6, 7), 24000) }
        assertEquals(5, buffer.frames)
    }
    @Test fun rejectsWrongSourceRateAndInvalidMicrophoneFixtures() {
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadPcm().append(shortArrayOf(1), 16000) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLoadReplay(ByteArray(256002)) }
    }
}
