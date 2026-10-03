package com.battlesbudz.jarvis.v2.memory

/** Strict attribution boundary: a local model selects spans; it cannot invent stored wording. */
object MemorySourceSupport {
    fun statementKind(fact: ExtractedMemory): MemoryStatementKind = if (fact.statementKind == MemoryStatementKind.TENTATIVE_INFERENCE ||
        Regex("\\b(?:think|suspect|probably|perhaps|seems|uncertain|may)\\b",RegexOption.IGNORE_CASE).containsMatchIn(fact.quote))
        MemoryStatementKind.TENTATIVE_INFERENCE else MemoryStatementKind.EXPLICIT_STATEMENT
    private val nonAssertion = Regex("\\b(?:assistant|system|tool|if|suppose|imagine|hypothetically|pretend|example|quoted|says|said|told|reported|according|wrote|might|maybe)\\b|[\"“”‘’]|(?:^|\\s)'[^']+'", RegexOption.IGNORE_CASE)
    private val userStatement = Regex("^(?:please remember(?: that)? |remember(?: that)? )?(?:i\\b|i'm\\b|i’ve\\b|i've\\b|my\\b|we\\b|our\\b)", RegexOption.IGNORE_CASE)
    fun supports(source: SourceEpisode, fact: ExtractedMemory): Boolean {
        if (fact.start < 0 || fact.end <= fact.start || fact.end > source.text.length) return false
        val span = source.text.substring(fact.start, fact.end)
        if (span != fact.quote || span.trim() != fact.content || !userStatement.containsMatchIn(span.trim())) return false
        // Reject indirect/quoted/hypothetical attribution anywhere in the submitted source,
        // even if a model selects only its apparently declarative inner clause.
        return !nonAssertion.containsMatchIn(source.text) && !source.text.trim().endsWith("?") && !span.trim().endsWith("?") && MemoryEligibility.eligibleFact(fact.content)
    }
}
