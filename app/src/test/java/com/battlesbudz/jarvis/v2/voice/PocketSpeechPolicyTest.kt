package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class PocketSpeechPolicyTest {
    @Test fun fillersAndSpeechShareVoiceSamplingAndOnlyDifferInDurationBudget() {
        val speech = PocketSpeechPolicy.extra(); val filler = PocketSpeechPolicy.extra(true)
        assertEquals("42", speech["seed"])
        assertEquals(speech, filler - "max_frames")
        assertEquals("50", filler["max_frames"])
        assertEquals(SpeechChunker.SENTENCE_LIMIT.toString(), speech["max_char_in_sentence"])
    }
    @Test fun newPolicyCannotReuseBuild607FillerCacheIdentity() {
        assertTrue(TtsEngine.POCKET_PAUL.version.contains(PocketSpeechPolicy.VERSION))
        assertFalse(TtsEngine.KOKORO.version.contains(PocketSpeechPolicy.VERSION))
    }
}
