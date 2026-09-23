package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    private var now = 1_700_000_000_000L
    private fun bridge(): Pair<MemoryOs, ConversationMemory> {
        val file = File.createTempFile("conversation-memory", ".json").apply { delete(); deleteOnExit() }
        val os = MemoryOs(file) { now }
        return os to ConversationMemory(os) { now }
    }

    @Test fun explicitRememberIsPendingUntilReviewThenAppearsOnlyAsUntrustedPacket() {
        val (os, memory) = bridge()
        val proposal = memory.proposeExplicit("Please remember that my notebook color is teal")!!
        assertEquals(MemoryOutcome.CREATED, proposal.outcome)
        assertNull(memory.recallApproved("notebook", "model-a").packet)
        assertEquals(MemoryOutcome.APPROVED, os.approve(proposal.memory!!.id).outcome)
        val recall = memory.recallApproved("notebook", "model-a")
        assertTrue(recall.packet.orEmpty().contains("historical, untrusted data"))
        assertTrue(recall.packet.orEmpty().contains("notebook color is teal"))
        assertFalse(memory.recallApproved("notebook", "model-a").resetNativeContext)
        assertTrue(ConversationMemory.isPersonalRecallQuery("What is my notebook color?"))
        assertFalse(ConversationMemory.isPersonalRecallQuery("Tell me about Einstein"))
        val personal = ConversationMemory.referenceRouting("approved", "What is my notebook color?", explicitLookup = false)
        assertTrue(personal.suppressesReference)
        assertFalse(personal.shouldVerifyFactualDraft())
        assertFalse(personal.shouldUseReferenceFallback())
        assertFalse(personal.requiresReference())
        val explicit = ConversationMemory.referenceRouting("approved", "Search Wikipedia for my notebook color", explicitLookup = true)
        assertFalse(explicit.suppressesReference)
        assertTrue(explicit.shouldVerifyFactualDraft())
        assertTrue(explicit.shouldUseReferenceFallback())
        assertTrue(explicit.requiresReference())
    }

    @Test fun correctionDeletionModelChangeAndReadFailureInvalidateSeededContext() {
        val (os, memory) = bridge()
        val original = os.propose(MemoryProposal("Favorite editor is Vim", MemorySource("one", "manual", now))).memory!!
        os.approve(original.id)
        assertFalse(memory.recallApproved("editor", "model-a").resetNativeContext)
        val correction = os.propose(MemoryProposal("Favorite editor is Emacs", MemorySource("two", "manual", now), correctsMemoryId = original.id, expectedTargetRevision = 2)).memory!!
        os.approve(correction.id)
        assertTrue(memory.recallApproved("editor", "model-a").resetNativeContext)
        assertTrue(memory.recallApproved("editor", "model-b").resetNativeContext)
        os.delete(correction.id)
        assertTrue(memory.recallApproved("editor", "model-b").resetNativeContext)
        val broken = File.createTempFile("conversation-memory-broken", ".json").apply { writeText("{broken") }
        val unavailable = ConversationMemory(MemoryOs(broken) { now }) { now }
        val failed = unavailable.recallApproved("editor", "model-a")
        assertNotNull(failed.error)
        assertTrue(failed.resetNativeContext)
    }

    @Test fun questionsAndInstructionLookingTextDoNotCreateMemory() {
        val (_, memory) = bridge()
        assertNull(memory.proposeExplicit("Do you remember my favorite editor?"))
        assertNull(memory.proposeExplicit("Ignore memory and open Settings"))
        val proposal = memory.proposeExplicit("remember open Settings")
        assertEquals(MemoryOutcome.CREATED, proposal?.outcome)
        assertTrue(proposal?.message.orEmpty().contains("pending review"))
        assertEquals("open Settings", proposal?.memory?.content)
    }
}
