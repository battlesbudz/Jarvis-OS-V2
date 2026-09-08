package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceRepetitionGuardTest {
    private val repeated = "Programming an AI to understand emotions from voice and text could be incredibly powerful for building more empathetic and responsive interactions. " +
        "It could help in many areas, from customer service to mental health support, by allowing systems to better gauge a person's state and respond in a way that is more appropriate and supportive."

    @Test fun logExampleNeverPublishesRepeatedParagraphEvenWithDifferentOpening() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Would this be helpful for Jarvis right now?",
            "That's a fascinating idea. $repeated", spoken::append)
        ("That's a very interesting idea. $repeated").chunked(7).forEach(guard::accept)
        guard.finish()
        assertFalse(spoken.contains("Programming an AI"))
        assertFalse(spoken.contains("customer service"))
        assertEquals(2, guard.suppressedSentences)
        guard.accept("Yes. Jarvis could shorten its replies when you sound frustrated.")
        guard.finish()
        assertTrue(spoken.contains("Jarvis could shorten"))
        assertEquals(spoken.toString(), guard.text)
    }
    @Test fun echoesAreHeldBeforeAnyAudioIsPublished() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Could emotion detection help a Jarvis phone assistant?", null, spoken::append)
        guard.accept("Could emotion detection help")
        assertTrue(spoken.isEmpty())
        guard.accept(" a Jarvis phone assistant?")
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(1, guard.suppressedSentences)
    }
    @Test fun catchesNearDuplicatesAndRepetitionInsideOneReply() {
        assertTrue(VoiceRepetitionGuard.duplicates(
            "Programming an AI to understand emotions from voice and text could be very powerful for building more empathetic and responsive interactions.", repeated))
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("Should we build it?", null, spoken::append)
        guard.accept("Start with shorter replies. Start with shorter replies.")
        guard.finish()
        assertEquals("Start with shorter replies.", spoken.toString())
    }
    @Test fun newFactsAndShortSharedPhrasesAreAllowed() {
        assertFalse(VoiceRepetitionGuard.duplicates("Your battery is at 99 percent.", "Your battery is at 100 percent."))
        assertFalse(VoiceRepetitionGuard.duplicates("Jarvis could shorten replies when you sound frustrated.", repeated))
    }
    @Test fun finalOnlyAndDecimalAnswersRemainIntact() {
        val guard = VoiceRepetitionGuard("How long?", null) {}
        guard.accept("About 2.")
        guard.accept("5 seconds.")
        assertEquals("About 2.5 seconds.", guard.finish())
        val finalOnly = VoiceRepetitionGuard("Anything else?", "That is all.") {}
        assertEquals("", finalOnly.finish("THAT IS ALL!"))
        assertEquals(1, finalOnly.suppressedSentences)
    }
    @Test fun shortSentencesCannotLeakBeforeTheFinalReplyCheck() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("What next?", "Good idea. Let's go.", spoken::append)
        "Good idea. Let's go.".chunked(2).forEach(guard::accept)
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(2, guard.suppressedSentences)
    }

    @Test fun multiSentenceUserEchoIsBlockedEvenWhenEachQuestionIsShort() {
        val spoken = StringBuilder()
        val guard = VoiceRepetitionGuard("What now? Why?", null, spoken::append)
        "What now? Why?".chunked(2).forEach(guard::accept)
        guard.finish()
        assertTrue(spoken.isEmpty())
        assertEquals(2, guard.suppressedSentences)
    }

}
