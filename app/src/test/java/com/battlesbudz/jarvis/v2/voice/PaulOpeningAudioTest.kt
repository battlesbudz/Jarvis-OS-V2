package com.battlesbudz.jarvis.v2.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PaulOpeningAudioTest {
    @Test fun shippedOpeningHasPinnedContentAndGentleEdges() {
        for ((asset, hash) in listOf(PaulOpeningAudio.ASSET to PaulOpeningAudio.SHA256,
            PaulOpeningAudio.RECOVERY_ASSET to PaulOpeningAudio.RECOVERY_SHA256)) {
        val file = listOf(File("src/main/assets/$asset"), File("app/src/main/assets/$asset")).first { it.exists() }
        val pcm = PaulOpeningAudio.decode(file.readBytes(), hash)
        assertTrue(pcm.size in 24000..96000)
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())
        assertTrue(FillerPcm.rms(pcm) in 0.02..0.20)
        assertTrue(pcm.none { kotlin.math.abs(it.toInt()) >= 32767 })
        }
    }
}
