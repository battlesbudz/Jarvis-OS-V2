package com.battlesbudz.jarvis.v2.memory

import java.util.UUID

/**
 * The small bridge between an explicit conversation request and the reviewed
 * local ledger. It never extracts facts automatically. A packet is only made
 * available after the caller has authorized the raw current action request.
 */
class ConversationMemory(private val memoryOs: MemoryOs, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Recall(val packet: String?, val error: String? = null, val resetNativeContext: Boolean = false)

    private var seededSignature: String? = null
    private var forceNativeReset = false

    fun proposeExplicit(userText: String): MemoryResult? {
        val content = explicitContent(userText) ?: return null
        val timestamp = now()
        val result = memoryOs.propose(MemoryProposal(
            content = content,
            source = MemorySource(UUID.randomUUID().toString(), "conversation_explicit", timestamp),
            category = MemoryCategory.OTHER,
            confidence = 70
        ))
        // A write can change the ledger generation even when it is still pending.
        seededSignature = null
        forceNativeReset = true
        return if (result.outcome == MemoryOutcome.CREATED) result.copy(
            message = "Memory proposal is pending review. Approve it in Memory before Jarvis can use it.") else result
    }

    /** Reads approved records only. A failed read is explicit and never looks like no matches. */
    fun recallApproved(query: String, modelId: String, budget: Int = 900): Recall {
        val packetResult = memoryOs.contextPacket(query, budget)
        if (packetResult.outcome != null) {
            seededSignature = null
            forceNativeReset = false
            return Recall(null, "Approved memory is unavailable: ${packetResult.message}", resetNativeContext = true)
        }
        // packet() always has a header; only a packet with selected records belongs in a prompt.
        val packet = packetResult.packet?.takeIf { it.memories.isNotEmpty() }?.text
        val signature = listOf(modelId, packet.orEmpty()).joinToString("\u0000")
        val changed = forceNativeReset || (seededSignature != null && seededSignature != signature)
        forceNativeReset = false
        seededSignature = signature
        return Recall(packet, resetNativeContext = changed)
    }

    fun invalidate() { seededSignature = null; forceNativeReset = true }

    companion object {
        data class ReferenceRouting(val suppressesReference: Boolean) {
            fun shouldVerifyFactualDraft(): Boolean = !suppressesReference
            fun shouldUseReferenceFallback(): Boolean = !suppressesReference
            fun requiresReference(): Boolean = !suppressesReference
        }

        /** Personal wording is eligible to answer from reviewed local history before web lookup. */
        fun isPersonalRecallQuery(text: String): Boolean = Regex(
            "\\b(my|mine)\\b|\\babout\\s+me\\b|\\bwhat\\s+(?:did|have|do)\\s+i\\b|\\b(?:remembered|saved)\\s+(?:about\\s+)?me\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        /** Explicit Wikipedia/lookup turns keep their requested reference behavior. */
        fun referenceRouting(approvedPacket: String?, prompt: String, explicitLookup: Boolean): ReferenceRouting =
            ReferenceRouting(approvedPacket != null && !explicitLookup && isPersonalRecallQuery(prompt))
    }

    internal fun explicitContent(userText: String): String? {
        val match = Regex("^\\s*(?:please\\s+)?remember(?:\\s+that)?\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)
            .matchEntire(userText) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }
}
