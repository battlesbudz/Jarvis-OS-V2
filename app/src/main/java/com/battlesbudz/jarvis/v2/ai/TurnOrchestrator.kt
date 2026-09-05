package com.battlesbudz.jarvis.v2.ai

enum class TurnKind {
    NORMAL_CHAT,
    FACTUAL_LOCAL_FIRST,
    EXPLICIT_LOOKUP,
    LOOKUP_CONFIRMATION
}

data class TurnPlan(
    val kind: TurnKind,
    val lookupQuery: String? = null
)

class TurnOrchestrator(
    private val grounding: ReferenceGroundingClient
) {
    private var pendingLookupSubject: String? = null
    private var activeSubjectQuestion: String? = null

    fun plan(prompt: String): TurnPlan {
        val confirmation = grounding.isLookupConfirmation(prompt)
        val explicit = grounding.isExplicitLookupRequest(prompt)
        if (confirmation && pendingLookupSubject != null) {
            return TurnPlan(
                kind = TurnKind.LOOKUP_CONFIRMATION,
                lookupQuery = pendingLookupSubject + "\n" + prompt
            )
        }
        if (explicit) {
            return TurnPlan(
                kind = TurnKind.EXPLICIT_LOOKUP,
                lookupQuery = pendingLookupSubject?.let { it + "\n" + prompt } ?: prompt
            )
        }

        if (!confirmation && !explicit && prompt.isNotBlank()) {
            val normalized = prompt.lowercase().trim()
            val isFollowUp = normalized.startsWith("what about") ||
                normalized.startsWith("how about") ||
                normalized.startsWith("and ") ||
                normalized.contains("him") ||
                normalized.contains("her ") ||
                normalized.contains("his ") ||
                normalized.contains("their ")
            activeSubjectQuestion = if (isFollowUp && activeSubjectQuestion != null) {
                activeSubjectQuestion + "\nFollow-up: " + prompt
            } else {
                prompt
            }
        }

        return if (grounding.shouldAutomaticallyLookup(prompt)) {
            TurnPlan(TurnKind.FACTUAL_LOCAL_FIRST)
        } else {
            TurnPlan(TurnKind.NORMAL_CHAT)
        }
    }

    fun automaticFallbackQuery(prompt: String): String = prompt

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
            pendingLookupSubject = activeSubjectQuestion ?: prompt
        } else {
            pendingLookupSubject = null
        }
    }

    fun pendingSubjectForDiagnostics(): String? = pendingLookupSubject
}
