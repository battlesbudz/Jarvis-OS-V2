package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOrchestratorTest {
    @Test
    fun namedEntityWithMiddleInitialTriggersAutomaticGrounding() {
        val grounding = ReferenceGroundingClient()

        assertTrue(grounding.shouldAutomaticallyLookup("Who is Harry J. Anslinger?"))
    }

    @Test
    fun lowercasePersonQuestionStillTriggersAutomaticGrounding() {
        assertTrue(ReferenceGroundingClient().shouldAutomaticallyLookup("who is jack herer"))
    }

    @Test
    fun whoNameWithoutIsStillTriggersAutomaticGrounding() {
        assertTrue(ReferenceGroundingClient().shouldAutomaticallyLookup("Who Jack Herer?"))
    }

    @Test
    fun knowledgeBaseFallbackAnswerIsInsufficient() {
        assertTrue(
            ReferenceGroundingClient().isInsufficientAnswer(
                "I do not have any specific information about Jack Herer in my current knowledge base."
            )
        )
    }

    @Test
    fun naturalApprovalPhrasesTriggerLookupConfirmation() {
        val grounding = ReferenceGroundingClient()

        assertTrue(grounding.isLookupConfirmation("Yes, of course."))
        assertTrue(grounding.isLookupConfirmation("Yeah, sounds like a good idea."))
        assertTrue(grounding.isLookupConfirmation("Go for it."))
        assertTrue(grounding.isLookupConfirmation("Yeah, of course."))
        assertTrue(grounding.isLookupConfirmation("Sure, I guess."))
    }

    @Test
    fun negativeReplyDoesNotTriggerLookupConfirmation() {
        assertTrue(!ReferenceGroundingClient().isLookupConfirmation("No, don't search."))
    }

    @Test
    fun factualTurnCarriesLookupQueryBeforeGeneration() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        val plan = orchestrator.plan("Who was Harry J. Anslinger?")

        assertEquals(TurnKind.FACTUAL_LOCAL_FIRST, plan.kind)
        assertEquals("Harry J. Anslinger", plan.lookupQuery)
    }

    @Test
    fun explicitLookupFollowUpRetainsTheNewestFactualSubject() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Hey there, Jarvis. Can you tell me about Harry J. Anslinger?")
        val plan = orchestrator.plan("Use Wikipedia.")

        assertEquals(TurnKind.EXPLICIT_LOOKUP, plan.kind)
        assertEquals("Harry J. Anslinger", plan.activeSubject)
        assertEquals("Harry J. Anslinger", plan.lookupQuery)
    }

    @Test
    fun explicitLookupDoesNotSearchForTheCommandAlone() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Who was Harry J. Anslinger?")
        val query = orchestrator.plan("Check Wikipedia for that.").lookupQuery

        assertEquals("Harry J. Anslinger", query)
    }
}
