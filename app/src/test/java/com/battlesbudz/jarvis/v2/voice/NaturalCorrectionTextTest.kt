package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class NaturalCorrectionTextTest {
    @Test fun retainsFinalAppCorrectionAfterRemovingLeadingEcho() {
        val text = NaturalCorrectionText.resolve(
            "The sky is blue. Open YouTube. Actually open Settings.", "The sky is blue.")!!
        assertEquals("Open YouTube. Actually open Settings.", text)
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
        assertTrue(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "Settings")))
    }
    @Test fun laterCancellationMustNeverBeDropped() {
        val text = NaturalCorrectionText.resolve("Open YouTube. Never mind, cancel.", "Hello there")!!
        assertTrue(text.contains("cancel"))
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
    }
    @Test fun echoOnlyOrEmptyFinalCannotDispatchProvisionalRequest() {
        assertNull(NaturalCorrectionText.resolve("Can you open Settings?", "Can you open Settings?"))
        assertNull(NaturalCorrectionText.resolve("", "The sky is blue"))
        assertNull(NaturalCorrectionText.resolve("mm hmm", "The sky is blue"))
    }
    @Test fun punctuationAndNamesRemainIntact() {
        assertEquals("Actually, tell me about New York.",
            NaturalCorrectionText.resolve("Actually, tell me about New York.", "Hello there"))
    }
}
