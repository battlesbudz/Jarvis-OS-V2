package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MemoryStoreTest {
    @Test fun reopensCommittedDataAndDoesNotClaimFailedWrites() {
        val file = temporaryFile()
        val os = MemoryOs(file) { 1_700_000_000_000L }
        val p = MemoryProposal("Tea is preferred", MemorySource("one", "manual", 1_700_000_000_000L))
        assertEquals(MemoryOutcome.CREATED, os.propose(p).outcome)
        assertEquals(1, MemoryOs(file) { 1_700_000_000_001L }.list().size)
        val broken = MemoryOs(MemoryStore(file) { _, _ -> throw IllegalStateException("disk full") }) { 1_700_000_000_002L }
        assertEquals(MemoryOutcome.STORAGE_FAILURE, broken.propose(MemoryProposal("Other", MemorySource("two", "manual", 1_700_000_000_002L))).outcome)
        assertEquals(1, MemoryOs(file).list().size)
    }

    @Test fun corruptionAndUnknownSchemaFailClosedWithoutOverwrite() {
        val file = temporaryFile(); file.writeText("{bad")
        val os = MemoryOs(file)
        assertEquals(MemoryOutcome.STORAGE_FAILURE, os.propose(MemoryProposal("x", MemorySource("a", "manual", 1))).outcome)
        assertEquals("{bad", file.readText())
        file.writeText("{\"schemaVersion\":99,\"generation\":0,\"memories\":[],\"tombstones\":[]}")
        assertEquals(MemoryOutcome.STORAGE_FAILURE, os.propose(MemoryProposal("x", MemorySource("a", "manual", 1))).outcome)
    }

    private fun temporaryFile(): File { val f = File.createTempFile("memory", ".json"); f.delete(); return f.apply { deleteOnExit() } }
}
