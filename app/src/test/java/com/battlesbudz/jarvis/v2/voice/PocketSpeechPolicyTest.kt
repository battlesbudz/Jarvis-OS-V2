package com.battlesbudz.jarvis.v2.voice
import org.junit.Assert.*
import org.junit.Test
class PocketSpeechPolicyTest {
    @Test fun fillersAndSpeechShareVoiceSamplingAndOnlyDifferInDurationBudget() {
        val speech = PocketSpeechPolicy.extra(); val filler = PocketSpeechPolicy.extra(true)
        assertEquals("42", speech["seed"])
        assertEquals(speech, filler - "max_frames")
        assertEquals("50", filler["max_frames"])
        assertFalse(speech.containsKey("max_char_in_sentence"))
        assertEquals("3", speech["first_chunk_size"])
        assertEquals("5", speech["chunk_size"])
        assertEquals("answer-id", PocketSpeechPolicy.extra(session = "answer-id")["jarvis_session"])
        assertFalse(PocketSpeechPolicy.extra(filler = true, session = "answer-id").containsKey("jarvis_session"))
    }
    @Test fun newPolicyCannotReuseBuild607FillerCacheIdentity() {
        assertTrue(TtsEngine.POCKET_PAUL.version.contains(PocketSpeechPolicy.VERSION))
        assertFalse(TtsEngine.KOKORO.version.contains(PocketSpeechPolicy.VERSION))
    }
}
