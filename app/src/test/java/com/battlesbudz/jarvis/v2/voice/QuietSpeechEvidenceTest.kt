package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class QuietSpeechEvidenceTest {
    @Test fun weakWhisperNeedsStableWords() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("what time", 0.23f, 0, false))
        assertFalse(evidence.accept("what time", 0.23f, 200, false))
        assertTrue(evidence.accept("what time", 0.23f, 350, false))
    }
    @Test fun noiseAndBlankTranscriptDoNotBecomeSpeech() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("", 0.23f, 0, false))
        assertFalse(evidence.accept("what time", 0.02f, 1000, false))
        assertFalse(evidence.accept("what time", 0.02f, 2000, false))
    }
    @Test fun weakSingleWordAndChangingFragmentsStayUnconfirmed() {
        val evidence = QuietSpeechEvidence()
        assertFalse(evidence.accept("the", 0.23f, 0, false))
        assertFalse(evidence.accept("the", 0.23f, 500, false))
        assertFalse(evidence.accept("the time", 0.23f, 600, false))
        assertFalse(evidence.accept("the time is", 0.23f, 700, false))
    }
    @Test fun staleWordsCannotTurnLaterWeakNoiseIntoSpeech() {
        val evidence = QuietSpeechEvidence()
        evidence.accept("what time", 0.23f, 0, false)
        assertFalse(evidence.accept("what time", 0.23f, 2000, false))
    }
    @Test fun singleProbabilitySpikeCannotBypassFrameConfirmation() {
        assertFalse(QuietSpeechEvidence().accept("yes", 0.8f, 0, false))
        assertTrue(QuietSpeechEvidence().accept("yes", 0.8f, 0, true))
    }
}
