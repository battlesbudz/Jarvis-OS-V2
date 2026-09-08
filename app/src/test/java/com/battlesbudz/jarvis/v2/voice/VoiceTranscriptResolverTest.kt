package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptResolverTest {
    @Test fun emptyAsrSendsOriginalAudioToGemma() = runBlocking {
        val wav = byteArrayOf(1, 2, 3, 4)
        var calls = 0
        val text = VoiceTranscriptResolver.resolve("", wav) {
            calls++
            assertSame(wav, it)
            "Open YouTube"
        }
        assertEquals("Open YouTube", text)
        assertEquals(1, calls)
    }
    @Test fun usableAsrDoesNotRequireAnExtraRecognitionPass() = runBlocking {
        assertEquals("Hello", VoiceTranscriptResolver.resolve("Hello", byteArrayOf()) { error("Unexpected fallback") })
    }
    @Test fun unintelligibleAudioGetsAnHonestPlaceholder() = runBlocking {
        assertEquals(VoiceTranscriptResolver.UNTRANSCRIBED,
            VoiceTranscriptResolver.resolve("", byteArrayOf()) { "[NO_SPEECH]" })
    }
}
