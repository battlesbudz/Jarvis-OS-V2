package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingAudioBufferTest {
    @Test
    fun retainsOnlyConfiguredRollingWindow() {
        val buffer = RollingAudioBuffer(AudioFormat(sampleRateHz = 1, channelCount = 1), maxDurationMs = 2_000)
        buffer.append(byteArrayOf(1, 2, 3))
        buffer.append(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(3, 4, 5), buffer.snapshot())
        assertEquals(3L, buffer.sizeBytes())
    }

    @Test
    fun clearDiscardsTemporaryAudio() {
        val buffer = RollingAudioBuffer()
        buffer.append(byteArrayOf(1, 2))
        buffer.clear()
        assertEquals(0L, buffer.sizeBytes())
        assertArrayEquals(byteArrayOf(), buffer.snapshot())
    }
}
