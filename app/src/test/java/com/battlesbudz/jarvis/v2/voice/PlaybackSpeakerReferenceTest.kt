package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeakerReferenceTest {
    @Test fun silentAndTooShortReferencesAreUnavailable() {
        assertNull(PlaybackSpeakerReference.fromPcm(ShortArray(22050), 22050))
        assertNull(PlaybackSpeakerReference.fromPcm(ShortArray(1000) { 1000 }, 22050))
    }
    @Test fun piperSamplesAreBoundedResampledAndNormalized() {
        val pcm = ShortArray(22050 * 10) { 16384 }
        val reference = PlaybackSpeakerReference.fromPcm(pcm, 22050)!!
        assertEquals(2, reference.windows.size)
        reference.windows.forEach { window ->
            assertEquals(24000, window.size)
            assertTrue(window.all { it == .5f })
        }
    }
}
