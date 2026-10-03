package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

internal fun extractionSource(text: String = "I like apricots", captured: Long = 1_000L) = SourceEpisode(
    MemoryPolicy.sourceKey("source-$captured"), MemoryPolicy.sourceKey("conversation"), null,
    ConversationMemorySource.TEXT, captured, captured + MemoryArchivePolicy.RETENTION_MS, text, "f".repeat(64))
internal fun extractionFact(source: SourceEpisode, sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    kind: MemoryStatementKind = MemoryStatementKind.EXPLICIT_STATEMENT) = ExtractedMemory(source.text.trim(), source.text, 0, source.text.length,
    MemoryCategory.PREFERENCE, sensitivity, kind)
internal class ExtractionLedger(var snapshot: MemorySnapshot) : MemoryPersistence {
    override fun read() = MemoryStore.Read(snapshot)
    override fun <T> update(block: (MemorySnapshot) -> Pair<MemorySnapshot,T>): MemoryStore.Update<T> {
        val (changed,value)=block(snapshot);snapshot=changed;return MemoryStore.Update(value=value)
    }
}
class MemoryAcceptanceTest {
    @Test fun supportedFactsAreAutomaticallyApprovedWithOriginalUserAttribution() {
        val source=extractionSource();val before=MemorySnapshot(0,emptyList(),emptyList())
        val memory=MemoryAcceptance.records(source,listOf(extractionFact(source)),before,2_000).single()
        assertEquals(MemoryAcceptanceOrigin.AUTOMATIC,memory.acceptanceOrigin)
        assertEquals(MemoryReviewStatus.APPROVED,memory.reviewStatus)
        assertEquals(source.capturedAtMs,memory.source.createdAtMs)
        assertEquals(source.eventKey,memory.source.provenance.first().id)
        assertTrue(MemoryPolicy.validatePersisted(memory))
        val after=before.copy(generation=1,memories=listOf(memory))
        assertTrue(MemoryAcceptance.records(source,listOf(extractionFact(source)),after,2_000).isEmpty())
    }
    @Test fun manualProposalsStayPendingAndTentativeLabelsSurviveCodecAndPacket() {
        val source=extractionSource();val ledger=ExtractionLedger(MemorySnapshot(0,emptyList(),emptyList()))
        val os=MemoryOs(ledger,{2_000L})
        assertEquals(MemoryReviewStatus.PENDING,os.propose(MemoryProposal("I like apricots",MemorySource("manual","manual entry",1_000))).memory!!.reviewStatus)
        val memory=MemoryAcceptance.records(source,listOf(extractionFact(source,kind=MemoryStatementKind.TENTATIVE_INFERENCE)),MemorySnapshot(0,emptyList(),emptyList()),2_000).single()
        val decoded=MemorySnapshotCodec.decodeMemory(MemorySnapshotCodec.encodeMemory(memory))
        assertEquals(MemoryStatementKind.TENTATIVE_INFERENCE,decoded.statementKind)
        assertTrue(MemoryRetrieval.packet(listOf(decoded),"apricots",2_000,2_000).text.contains("TENTATIVE_INFERENCE"))
    }
}
