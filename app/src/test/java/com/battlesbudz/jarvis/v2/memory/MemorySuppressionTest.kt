package com.battlesbudz.jarvis.v2.memory
import org.junit.Assert.*
import org.junit.Test
class MemorySuppressionTest {
    @Test fun erasureBlocksOlderSourcesEvenDifferentIdsAndParaphrasedDerivedFacts() {
        val old=extractionSource();val record=MemoryAcceptance.records(old,listOf(extractionFact(old)),MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
        val ledger=ExtractionLedger(MemorySnapshot(1,listOf(record),emptyList()))
        assertEquals(MemoryOutcome.DELETED,MemoryOs(ledger,{2_000L}).delete(record.id).outcome)
        val different=extractionSource("I enjoy apricots",1_500).copy(eventKey=MemoryPolicy.sourceKey("copy"))
        assertTrue(MemorySuppression.suppressed(different,ledger.snapshot))
        assertTrue(MemoryAcceptance.records(different,listOf(extractionFact(different)),ledger.snapshot,2_000).isEmpty())
        val fresh=extractionSource("I now like plums",2_001)
        assertFalse(MemorySuppression.suppressed(fresh,ledger.snapshot))
        assertEquals(1,MemoryAcceptance.records(fresh,listOf(extractionFact(fresh)),ledger.snapshot,2_001).size)
    }
}
