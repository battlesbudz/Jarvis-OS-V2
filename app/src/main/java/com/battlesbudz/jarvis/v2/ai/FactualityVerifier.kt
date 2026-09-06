package com.battlesbudz.jarvis.v2.ai

class FactualityVerifier {
    fun buildPrompt(question: String, draft: String): String {
        return """
            You are a factuality gate for a local assistant.
            Decide whether the draft is reliable enough to show as an answer.
            Return exactly one word: PASS or LOOKUP.
            Return LOOKUP if the draft is uncertain, contradicts the question,
            confuses similarly named people or works, invents details, or makes
            historical/factual claims without enough confidence.
            Return PASS only when the draft directly answers the question and
            appears factually grounded in the model's knowledge.
            
            Question:
            $question
            
            Draft:
            $draft
        """.trimIndent()
    }

    fun requestsLookup(verdict: String): Boolean {
        val normalized = verdict.trim().uppercase()
        return normalized.startsWith("LOOKUP") ||
            normalized.contains("NEEDS_WIKIPEDIA") ||
            normalized.contains("NEEDS_REFERENCE")
    }
}
