package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOrchestratorTest {
    @Test
    fun explicitLookupFollowUpRetainsTheNewestFactualSubject() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Hey there, Jarvis. Can you tell me about Harry J. Anslinger?")
        val plan = orchestrator.plan("Use Wikipedia.")

        assertEquals(TurnKind.EXPLICIT_LOOKUP, plan.kind)
        assertEquals("Harry J. Anslinger", plan.activeSubject)
        assertTrue(plan.lookupQuery!!.contains("Harry J. Anslinger"))
        assertTrue(plan.lookupQuery!!.contains("Use Wikipedia."))
    }

    @Test
    fun explicitLookupDoesNotSearchForTheCommandAlone() {
        val orchestrator = TurnOrchestrator(ReferenceGroundingClient())

        orchestrator.plan("Who was Harry J. Anslinger?")
        val query = orchestrator.plan("Check Wikipedia for that.").lookupQuery

        assertTrue(query!!.contains("Harry J. Anslinger"))
        assertTrue(!query.equals("Check Wikipedia for that."))
    }
}
