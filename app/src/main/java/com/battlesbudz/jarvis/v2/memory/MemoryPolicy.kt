package com.battlesbudz.jarvis.v2.memory

import java.security.MessageDigest

object MemoryPolicy {
    const val MAX_CONTENT_CHARS = 2_000
    const val MAX_EVENT_ID_CHARS = 128
    const val MAX_EVENT_SOURCE_CHARS = 128
    const val MAX_PROVENANCE = 8
    const val MAX_MEMORIES = 500
    const val MAX_TOMBSTONES = 2_000

    data class Decision(val outcome: MemoryOutcome?, val message: String) { val allowed get() = outcome == null }

    fun assess(proposal: MemoryProposal, nowMs: Long): Decision {
        val content = proposal.content.trim()
        if (content.isEmpty() || content.length > MAX_CONTENT_CHARS) return Decision(MemoryOutcome.INVALID, "Memory content must be 1-$MAX_CONTENT_CHARS characters.")
        if (proposal.source.eventId.isBlank() || proposal.source.eventId.length > MAX_EVENT_ID_CHARS ||
            proposal.source.eventSource.isBlank() || proposal.source.eventSource.length > MAX_EVENT_SOURCE_CHARS) return Decision(MemoryOutcome.INVALID, "A bounded source event is required.")
        if (proposal.confidence !in 0..100) return Decision(MemoryOutcome.INVALID, "Confidence must be 0-100.")
        if (proposal.source.createdAtMs <= 0 || proposal.source.createdAtMs > nowMs + 86_400_000L) return Decision(MemoryOutcome.INVALID, "Source timestamp is invalid.")
        if (proposal.expiresAtMs != null && proposal.expiresAtMs <= nowMs) return Decision(MemoryOutcome.INVALID, "Expiry must be in the future.")
        if (proposal.source.provenance.size > MAX_PROVENANCE || proposal.source.provenance.any { it.restricted || it.kind.length > 96 || it.id.length > 160 } || isRestrictedSource(proposal.source.eventSource))
            return Decision(MemoryOutcome.EXCLUDED, "Restricted or invalid provenance cannot be stored.")
        if (proposal.source.sensitivity == MemorySensitivity.RESTRICTED || containsRawRestrictedContent(content))
            return Decision(MemoryOutcome.EXCLUDED, "Raw financial or identity data is not stored in memory.")
        return Decision(null, "ok")
    }

    private fun isRestrictedSource(value: String): Boolean {
        val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        return listOf("bank", "banking", "financial", "transaction", "credit_card", "debit_card", "payroll", "brokerage", "restricted_source", "restricted_summary").any { token -> normalized == token || normalized.contains("_${token}_") || normalized.startsWith("${token}_") || normalized.endsWith("_${token}") }
    }

    /** Conservative bounded detectors, deliberately not a claim to detect every secret. */
    fun containsRawRestrictedContent(content: String): Boolean {
        val bounded = content.take(MAX_CONTENT_CHARS)
        val patterns = listOf(
            "\\b(?:account|routing|card|debit|credit)\\s*(?:number|no\\.?|#|ending(?:\\s+in)?|last\\s+four)?\\s*[:#-]?\\s*(?:\\d[\\s-]?){4,}\\b",
            "\\blast\\s+four\\s*(?:digits?)?\\s*(?:are|is|[:#-])?\\s*(?:\\d[\\s-]?){4}\\b",
            "\\b(?:ssn|social security)\\b.{0,40}\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{4}\\b",
            "\\b(?:available|current|ending)\\s+balance\\b.{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\b(?:bank|checking|savings|account)\\s+balance\\b.{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\bbalance\\b.{0,40}\\b(?:bank|checking|savings|account)\\b.{0,80}\\$?\\d[\\d,]*(?:\\.\\d{2})?\\b",
            "\\b(?:bank|checking|savings|account)(?:\\s+account)?\\b.{0,80}(?:\\$\\d[\\d,]*(?:\\.\\d{2})?|\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?\\b)",
            "(?:\\$\\d[\\d,]*(?:\\.\\d{2})?|\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{2})?\\b).{0,80}\\b(?:bank|checking|savings|account)(?:\\s+account)?\\b",
            "^\\s*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+.{2,}\\s+[-+]?\\$?\\d[\\d,]*(?:\\.\\d{2})?\\s*$",
            "^\\s*\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s*,\\s*[^,\\n]{2,}\\s*,\\s*[-+]?\\$?\\d[\\d,]*(?:\\.\\d{2})?\\s*$",
        )
        return patterns.any { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(bounded) }
    }

    /** Opaque persisted event key: deletion never retains a caller supplied identifier. */
    fun sourceKey(eventId: String): String = sha256("memory-event\u0000" + eventId.trim()).take(32)

    fun fingerprint(proposal: MemoryProposal): String = sha256(listOf(
        proposal.content.trim(), proposal.category.name, proposal.tier.name, proposal.type.name, proposal.confidence.toString(),
        proposal.expiresAtMs?.toString().orEmpty(), proposal.correctsMemoryId.orEmpty(), proposal.source.eventSource.trim(),
        proposal.source.createdAtMs.toString(), proposal.source.sensitivity.name,
        proposal.source.provenance.joinToString("|") { "${it.kind}:${it.id}:${it.label.orEmpty()}:${it.restricted}" },
    ).joinToString("\u0000"))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
