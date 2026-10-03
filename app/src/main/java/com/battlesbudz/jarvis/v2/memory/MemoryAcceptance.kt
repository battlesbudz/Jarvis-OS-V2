package com.battlesbudz.jarvis.v2.memory

import java.util.UUID

object MemoryAcceptance {
    /** Used within the same durable transaction as job completion/index invalidation. */
    fun records(source: SourceEpisode, facts: List<ExtractedMemory>, before: MemorySnapshot, now: Long): List<MemoryRecord> {
        require(MemoryEligibility.eligibleSource(source, now))
        if (MemorySuppression.suppressed(source, before)) return emptyList()
        require(facts.size <= 8 && facts.all { MemorySourceSupport.supports(source, it) })
        return facts.map { fact ->
            val event = MemoryPolicy.sourceKey("extraction:${source.eventKey}:${fact.start}:${fact.end}")
            val sensitivity = MemorySensitivityPolicy.classify(fact.content, fact.sensitivity)
            val provenance = listOf(MemoryProvenance("source_episode", source.eventKey), MemoryProvenance("conversation", source.conversationKey)) +
                listOfNotNull(source.callKey?.let { MemoryProvenance("call", it) })
            val origin = MemorySource(event, "local_user_extraction", source.capturedAtMs, sensitivity, provenance)
            val kind = MemorySourceSupport.statementKind(fact)
            val confidence = if (kind == MemoryStatementKind.EXPLICIT_STATEMENT) 90 else 50
            MemoryRecord(UUID.randomUUID().toString(), fact.content, fact.category, MemoryTier.LONG_TERM, MemoryType.SEMANTIC,
                confidence, origin, MemoryReviewStatus.APPROVED,
                now, now, 1, payloadFingerprint = MemoryPolicy.fingerprint(MemoryProposal(fact.content, origin, fact.category, confidence = confidence)),
                acceptanceOrigin = MemoryAcceptanceOrigin.AUTOMATIC, statementKind = kind)
        }.filter { candidate -> before.memories.none { it.source.eventId == candidate.source.eventId ||
            (it.acceptanceOrigin == MemoryAcceptanceOrigin.AUTOMATIC && it.content == candidate.content && it.source.createdAtMs == candidate.source.createdAtMs) } && before.tombstones.none { it.eventId == candidate.source.eventId } }
    }
}
