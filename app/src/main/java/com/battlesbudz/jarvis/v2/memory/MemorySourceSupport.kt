package com.battlesbudz.jarvis.v2.memory

/** Conservative complete-statement boundary, not a general natural-language entailment parser. */
object MemorySourceSupport {
    private val uncertainty = Regex("\\b(?:think|believe|suspect|guess|estimate|assume|expect|probably|perhaps|possibly|seems?|uncertain|unsure|sure|certain|convinced|may|might|could|would|should|apparently|likely|unlikely|hope|hoping|wish|dream\\w*)\\b", RegexOption.IGNORE_CASE)
    fun statementKind(source: SourceEpisode, fact: ExtractedMemory): MemoryStatementKind = if (fact.statementKind == MemoryStatementKind.TENTATIVE_INFERENCE ||
        uncertainty.containsMatchIn(source.text))
        MemoryStatementKind.TENTATIVE_INFERENCE else MemoryStatementKind.EXPLICIT_STATEMENT
    private val nonAssertion = Regex("\\b(?:assistant|system|tool|if|suppose|imagine|hypothetically|pretend|example|quoted|says|said|told|reported|according|wrote|maybe)\\b|[\"“”‘’]|(?:^|\\s)'[^']+'", RegexOption.IGNORE_CASE)
    // Closed supported syntax prevents reported third-party beliefs from becoming self facts.
    // Full-source storage preserves negation and every accepted qualifier verbatim.
    private val directStatement = Regex("^(?:i|we) (?:am|are|have|has|live|lived|work|worked|like|liked|love|loved|prefer|preferred|enjoy|enjoyed|hate|dislike|own|owned|need|want|plan|keep|use|study|studied|speak|wear|take|avoid|remember|(?:do not|did not|don't|didn't) (?:have|live|work|like|love|prefer|enjoy|hate|dislike|own|need|want|keep|use|study|speak|wear|take|avoid)) [^.!?\\r\\n]+[.!]?$", RegexOption.IGNORE_CASE)
    private val ownedAttribute = Regex("^(?:my|our) (?:name|age|birthday|address|diagnosis|medication|condition|allergy|job|occupation|role|salary|income|religion|goal|preference|favorite [a-z ]{1,40}) (?:is|are|was|were|may be|might be|could be|seems to be) [^.!?\\r\\n]+[.!]?$", RegexOption.IGNORE_CASE)
    private val tentativeFrame = Regex("^(?:i|we) (?:think|believe|suspect|guess|estimate|assume|expect)(?: that)? (.+)$", RegexOption.IGNORE_CASE)
    private fun supportedStatement(text: String): Boolean {
        val statement = text.removePrefixIgnoringCase("please remember that ")
            .removePrefixIgnoringCase("please remember ").removePrefixIgnoringCase("remember that ").removePrefixIgnoringCase("remember ")
            .replace(Regex("^i'm ", RegexOption.IGNORE_CASE), "I am ")
            .replace(Regex("^i've ", RegexOption.IGNORE_CASE), "I have ")
        val body = tentativeFrame.matchEntire(statement)?.groupValues?.get(1) ?: statement
        return directStatement.matches(body) || ownedAttribute.matches(body)
    }
    private fun String.removePrefixIgnoringCase(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
    fun supports(source: SourceEpisode, fact: ExtractedMemory): Boolean {
        if (fact.start < 0 || fact.end <= fact.start || fact.end > source.text.length) return false
        val span = source.text.substring(fact.start, fact.end)
        val first = source.text.indexOfFirst { !it.isWhitespace() }
        val last = source.text.indexOfLast { !it.isWhitespace() } + 1
        // An inner span has no independent authority to discard the outer source's speaker,
        // modality, negation or uncertainty. Accept exactly one complete trimmed statement.
        if (fact.start != first || fact.end != last || span != fact.quote || span != fact.content) return false
        return !nonAssertion.containsMatchIn(source.text) && supportedStatement(span) && MemoryEligibility.eligibleFact(fact.content)
    }
}
