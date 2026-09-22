package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MemoryOsTest {
    private var now = 1_700_000_000_000L
    private fun os(file: File = temp()) = MemoryOs(file) { now }
    private fun source(id: String) = MemorySource(id, "manual", now)

    @Test fun sourceIdempotencyCollisionAndCorrectionApprovalAreAtomic() {
        val os = os()
        val original = os.propose(MemoryProposal("Lives in Austin", source("a"))).memory!!
        assertEquals(MemoryOutcome.ALREADY_RECORDED, os.propose(MemoryProposal("Lives in Austin", source("a"))).outcome)
        assertEquals(MemoryOutcome.CONFLICT, os.propose(MemoryProposal("Lives in Dallas", source("a"))).outcome)
        assertEquals(MemoryOutcome.APPROVED, os.approve(original.id, original.revision).outcome)
        now++
        val correction = os.propose(MemoryProposal("Lives in Dallas", source("b"), correctsMemoryId = original.id, expectedTargetRevision = 2)).memory!!
        assertEquals(MemoryOutcome.CONFLICT, os.propose(MemoryProposal("Elsewhere", source("c"), correctsMemoryId = original.id)).outcome)
        assertEquals(MemoryOutcome.APPROVED, os.approve(correction.id).outcome)
        val all = os.read().snapshot!!.memories; assertEquals(MemoryReviewStatus.SUPERSEDED, all.first { it.id == original.id }.reviewStatus); assertEquals(MemoryReviewStatus.APPROVED, all.first { it.id == correction.id }.reviewStatus)
    }

    @Test fun expiringProposalReopensExpiresFromRetrievalAndCanBeErased() {
        val file = temp(); val first = os(file)
        val created = first.propose(MemoryProposal("Tea preference", source("expiry"), expiresAtMs = now + 60_000)).memory!!
        assertEquals(MemoryOutcome.CREATED, first.propose(MemoryProposal("other", source("expiry2"), expiresAtMs = now + 60_000)).outcome)
        assertEquals(MemoryOutcome.APPROVED, first.approve(created.id).outcome)
        val reopened = os(file)
        assertEquals(1, reopened.retrieveResult("tea", nowMs = now).memories.size)
        now += 60_001
        assertTrue(reopened.retrieveResult("tea", nowMs = now).memories.isEmpty())
        assertEquals(MemoryOutcome.DELETED, reopened.delete(created.id).outcome)
    }

    @Test fun restrictedMetadataNeverMutatesTheLedgerAndBoundaryFingerprintsConflict() {
        val file = temp(); val os = os(file)
        val restricted = MemoryProposal("safe", source("r").copy(provenance = listOf(MemoryProvenance("note", "x", "My SSN is\n123-45-6789"))))
        assertEquals(MemoryOutcome.EXCLUDED, os.propose(restricted).outcome)
        assertFalse(file.exists())
        val a = MemoryProposal("same", source("shared").copy(provenance = listOf(MemoryProvenance("note", "a:b", "c"))))
        val b = MemoryProposal("same", source("shared").copy(provenance = listOf(MemoryProvenance("note", "a", "b:c"))))
        assertEquals(MemoryOutcome.CREATED, os.propose(a).outcome)
        assertEquals(MemoryOutcome.CONFLICT, os.propose(b).outcome)
    }

    @Test fun admissionReservesTombstoneCapacityForWholeLineageErase() {
        val file = temp(); val store = MemoryStore(file); val os = MemoryOs(store) { now }
        val tombstones = (1 until MemoryPolicy.MAX_TOMBSTONES).map { i -> MemoryTombstone(MemoryPolicy.sourceKey("old$i"), "a".repeat(64), now) }
        assertNull(store.update { before -> before.copy(tombstones = tombstones) to Unit }.error)
        val created = os.propose(MemoryProposal("erasable", source("new"))).memory!!
        assertEquals(MemoryOutcome.DELETED, os.delete(created.id).outcome)
        assertEquals(MemoryPolicy.MAX_TOMBSTONES, os.read().snapshot!!.tombstones.size)
        assertEquals(MemoryOutcome.INVALID, os.propose(MemoryProposal("cannot reserve deletion", source("last"))).outcome)
    }

    @Test fun separateStoreInstancesSerializeSameSourceEvent() {
        val file = temp(); val first = MemoryOs(file) { now }; val second = MemoryOs(file) { now }
        val results = arrayOfNulls<MemoryResult>(2)
        val a = Thread { results[0] = first.propose(MemoryProposal("shared", source("same"))) }
        val b = Thread { results[1] = second.propose(MemoryProposal("shared", source("same"))) }
        a.start(); b.start(); a.join(); b.join()
        assertEquals(setOf(MemoryOutcome.CREATED, MemoryOutcome.ALREADY_RECORDED), results.map { it!!.outcome }.toSet())
        assertEquals(1, first.read().snapshot!!.memories.size)
    }

    @Test fun staleReviewAndLineageDeleteAreSafeAndDoNotResurrect() {
        val os = os(); val old = os.propose(MemoryProposal("old", source("old"))).memory!!; os.approve(old.id)
        val replacement = os.propose(MemoryProposal("new", source("new"), correctsMemoryId = old.id)).memory!!
        assertEquals(MemoryOutcome.CONFLICT, os.approve(replacement.id, 99).outcome)
        assertEquals(MemoryOutcome.DELETED, os.delete(replacement.id).outcome)
        assertTrue(os.read().snapshot!!.memories.isEmpty())
        assertEquals(MemoryOutcome.DELETED, os.propose(MemoryProposal("new", source("new"), correctsMemoryId = old.id)).outcome)
    }

    private fun temp(): File = File.createTempFile("memory-os", ".json").apply { delete(); deleteOnExit() }
}
