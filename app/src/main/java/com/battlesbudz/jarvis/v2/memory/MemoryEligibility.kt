package com.battlesbudz.jarvis.v2.memory

/** Save eligibility does not depend on screen unlock. Disclosure is a separate decision. */
object MemoryEligibility {
    fun eligibleSource(source: SourceEpisode, nowMs: Long): Boolean =
        SourceTextPersistencePolicy.eligible(source.capturedAtMs, nowMs) &&
            source.expiresAtMs == source.capturedAtMs + MemoryArchivePolicy.RETENTION_MS &&
            !SourceTextPersistencePolicy.excluded(source.text)

    fun eligibleFact(content: String): Boolean = content.isNotBlank() &&
        content.length <= MemoryPolicy.MAX_CONTENT_CHARS && !MemoryArchivePolicy.containsSecret(content)
}
