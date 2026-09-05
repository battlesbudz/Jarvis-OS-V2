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
