package com.battlesbudz.jarvis.v2.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PaulOpeningAudioTest {
    @Test fun shippedOpeningHasPinnedContentAndGentleEdges() {
        val file = listOf(File("src/main/assets/voice/paul-umm-v1.wav"), File("app/src/main/assets/voice/paul-umm-v1.wav")).first { it.exists() }
        val pcm = PaulOpeningAudio.decode(file.readBytes())
        assertTrue(pcm.size in 16800..21600)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        assertTrue(FillerPcm.rms(pcm) in 0.02..0.20)
        assertTrue(pcm.none { kotlin.math.abs(it.toInt()) >= 32767 })
    }
}
