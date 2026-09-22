package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    private val now = 1_700_000_000_000L
    private fun proposal(content: String, source: MemorySource = MemorySource("evt", "manual", now)) = MemoryProposal(content, source)

    @Test fun rawFinancialAndIdentityContentAndRestrictedProvenanceAreExcluded() {
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("My SSN is 123-45-6789"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("Checking account balance is $1,234.00"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("safe", MemorySource("evt2", "manual", now, provenance = listOf(MemoryProvenance("bank", "x", restricted = true)))), now).outcome)
    }

    @Test fun invalidBoundsAreHonestAndSourceKeysAreOpaque() {
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal(" "), now).outcome)
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal("x", MemorySource("evt", "manual", now + 90_000_000L)), now).outcome)
        assertNotEquals("a phrase from the caller", MemoryPolicy.sourceKey("a phrase from the caller"))
        assertEquals(MemoryPolicy.sourceKey("same"), MemoryPolicy.sourceKey("same"))
    }
}
