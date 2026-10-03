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
    @Test fun legacyStoreClockConstructorsAndExplicitDisclosureCallbacksRemainDistinct() {
        var now=2_000L
        val source=extractionSource("My diagnosis is asthma")
        val sensitive=MemoryAcceptance.records(source,listOf(extractionFact(source)),MemorySnapshot(0,emptyList(),emptyList()),1_000).single()
        val snapshot=MemorySnapshot(1,listOf(sensitive),emptyList())
        val factories=listOf<(MemoryPersistence)->MemoryOs>(
            { store -> MemoryOs(store) { now } },
            { store -> MemoryOs(store,{now}) },
            { store -> MemoryOs(store,clock={now}) }
        )
        factories.forEachIndexed { index,factory ->
            val os=factory(ExtractionLedger(snapshot));now++
            assertTrue(os.read().snapshot!!.memories.isEmpty())
            val proposed=os.propose(MemoryProposal("I like apricots",MemorySource("clock-$index","manual entry",1_000)))
            assertEquals(now,proposed.memory!!.createdAtMs)
        }
        val ledger=ExtractionLedger(snapshot)
        assertTrue(MemoryOs(ledger).read().snapshot!!.memories.isEmpty())
        var unlocked=true
        val explicit=MemoryOs(ledger,{now},{unlocked})
        val named=MemoryOs(ledger,clock={now},canDiscloseSensitive={unlocked})
        val defaultClock=MemoryOs(ledger,clock={System.currentTimeMillis()},canDiscloseSensitive={unlocked})
        listOf(explicit,named,defaultClock).forEach { assertEquals(sensitive.id,it.read().snapshot!!.memories.single().id) }
        unlocked=false
        listOf(explicit,named,defaultClock).forEach { assertTrue(it.read().snapshot!!.memories.isEmpty()) }
        val directory=java.nio.file.Files.createTempDirectory("memory-clock").toFile()
        try {
            val file=java.io.File(directory,"memory.json")
            val os=MemoryOs(file) { now }
            val proposed=os.propose(MemoryProposal("I like plums",MemorySource("file-clock","manual entry",1_000)))
            assertEquals(now,proposed.memory!!.createdAtMs)
        } finally { directory.deleteRecursively() }
    }
}
