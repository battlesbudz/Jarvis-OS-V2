package com.battlesbudz.jarvis.v2.memory
import org.junit.Assert.*
import org.junit.Test
class MemoryEligibilityTest {
    @Test fun sourceEligibilityRejectsSecretSuffixInvalidClocksAndExactExpiry() {
        val source=extractionSource()
        assertTrue(MemoryEligibility.eligibleSource(source,1_000))
        assertFalse(MemoryEligibility.eligibleSource(source,source.expiresAtMs))
        assertFalse(MemoryEligibility.eligibleSource(source.copy(capturedAtMs=0),1_000))
        assertFalse(MemoryEligibility.eligibleSource(source.copy(capturedAtMs=Long.MAX_VALUE),1_000))
        assertFalse(MemoryEligibility.eligibleSource(extractionSource("I like apricots "+"x".repeat(3_000)+" password: tiny"),1_000))
        assertFalse(MemoryEligibility.eligibleFact("My access code is 1234"))
    }
    @Test fun sensitiveSavingIsIndependentOfUnlock() {
        val source=extractionSource("My diagnosis is asthma")
        assertTrue(MemoryEligibility.eligibleSource(source,1_000))
        val record=MemoryAcceptance.records(source,listOf(extractionFact(source)),MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
        assertEquals(MemorySensitivity.RESTRICTED,record.source.sensitivity)
        assertTrue(MemoryPolicy.validatePersisted(record))
        assertFalse(MemorySensitivityPolicy.mayDisclose(record,false))
    }
}
