package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

class ReferenceEvidencePolicyTest {
    @Test fun acronymRequiresBothTermAndConversationDomain() {
        val query = "FPJ\nConversation domain: I'm making nutrients using Korean Natural Farming"
        assertFalse(ReferenceEvidencePolicy.relevant(query, "Fernando Poe Jr.", "FPJ was an actor and politician."))
        assertTrue(ReferenceEvidencePolicy.relevant(query, "Korean Natural Farming", "Fermented plant juice (FPJ) is used in Korean Natural Farming."))
    }
    @Test fun holidayTriviaCannotVerifyShopOffer() {
        assertFalse(ReferenceEvidencePolicy.relevant("Today is National Coffee Day and Stewart says free coffee", "International Coffee Day", "National coffee celebrations include free coffee."))
    }
    @Test fun retrievesRelevantBodyParagraphBeyondIntro() {
        val article = "Korean Natural Farming uses indigenous microorganisms.\n\n" + "Unrelated history.\n".repeat(200) + "Fermented plant juice (FPJ) uses brown sugar and young plants in Korean Natural Farming."
        val passage = ReferenceEvidencePolicy.passage("FPJ\nConversation domain: Korean Natural Farming", "Korean Natural Farming", article)
        assertTrue(passage.contains("brown sugar")); assertTrue(passage.length <= 1800)
    }
    @Test fun missingAcronymOrEmptyEvidenceIsNotSuccess() {
        assertFalse(ReferenceEvidencePolicy.relevant("FPJ\nConversation domain: Korean Natural Farming", "Korean Natural Farming", "Uses indigenous microorganisms."))
        assertFalse(ReferenceEvidencePolicy.relevant("Jack Herer", "Jack Herer", ""))
    }
}
