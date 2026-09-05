package com.battlesbudz.jarvis.v2.ai

enum class TurnKind {
    NORMAL_CHAT,
    FACTUAL_LOCAL_FIRST,
    EXPLICIT_LOOKUP,
    LOOKUP_CONFIRMATION
}

data class TurnPlan(
    val kind: TurnKind,
    val lookupQuery: String? = null,
    val activeSubject: String? = null
)

class TurnOrchestrator(
    private val grounding: ReferenceGroundingClient
) {
    private var pendingLookupSubject: String? = null
    private var activeSubjectQuestion: String? = null
    private var activeSubject: String? = null

    fun plan(prompt: String): TurnPlan {
        val confirmation = grounding.isLookupConfirmation(prompt)
        val explicit = grounding.isExplicitLookupRequest(prompt)
        if (confirmation && pendingLookupSubject != null) {
            return TurnPlan(
                kind = TurnKind.LOOKUP_CONFIRMATION,
                lookupQuery = pendingLookupSubject + "\n" + prompt,
                activeSubject = activeSubject
            )
        }
        if (explicit) {
            return TurnPlan(
                kind = TurnKind.EXPLICIT_LOOKUP,
                lookupQuery = pendingLookupSubject?.let { it + "\n" + prompt } ?: prompt,
                activeSubject = activeSubject
            )
        }

        if (!confirmation && !explicit && prompt.isNotBlank()) {
            val normalized = prompt.lowercase().trim()
            val namedEntity = extractNamedEntity(prompt)
            val isFollowUp = normalized.startsWith("what about") ||
                normalized.startsWith("how about") ||
                normalized.startsWith("and ") ||
                normalized.contains(" him") ||
                normalized.contains(" her ") ||
                normalized.contains(" his ") ||
                normalized.contains(" their ")
            if (namedEntity != null) {
                activeSubject = namedEntity
            }
            activeSubjectQuestion = if (isFollowUp && activeSubjectQuestion != null) {
                activeSubjectQuestion + "\nFollow-up: " + prompt
            } else {
                prompt
            }
        }

        val kind = if (grounding.shouldAutomaticallyLookup(prompt)) {
            TurnKind.FACTUAL_LOCAL_FIRST
        } else {
            TurnKind.NORMAL_CHAT
        }
        return TurnPlan(kind, activeSubject = activeSubject)
    }

    fun automaticFallbackQuery(prompt: String): String =
        activeSubject?.let { it + "\n" + prompt } ?: prompt

    fun recordResponse(prompt: String, response: String, plan: TurnPlan) {
        val normalized = response.lowercase()
        if (plan.kind == TurnKind.EXPLICIT_LOOKUP ||
            plan.kind == TurnKind.LOOKUP_CONFIRMATION
        ) {
            pendingLookupSubject = null
            return
        }
        if (grounding.isInsufficientAnswer(response) ||
            normalized.contains("would you like me to search wikipedia") ||
            normalized.contains("would you like me to search wikidata")
        ) {
            pendingLookupSubject = activeSubject ?: prompt
        } else {
            pendingLookupSubject = null
        }
    }

    fun pendingSubjectForDiagnostics(): String? = pendingLookupSubject
}    private fun extractNamedEntity(prompt: String): String? {
        val candidates = Regex(
            "\\b[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,}){1,4}\\b"
        ).findAll(prompt).map { it.value.trim() }.toList()
        return candidates.lastOrNull { candidate ->
            candidate.lowercase() !in setOf(
                "Can You", "What Is", "What Was", "Who Was", "Tell Me",
                "How Did", "What About", "How About"
            )
        } ?: Regex("\\b[A-Z]{2,}(?:\\s+[A-Z]{2,})*\\b")
            .find(prompt)?.value
    }


