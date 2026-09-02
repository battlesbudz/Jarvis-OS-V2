package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine

/**
 * FunctionGemma MobileActions-270M produces requests; Kotlin validates them.
 * Generated text is never executed directly.
 */
class MobileActionRouter(
    private val actionModel: LocalModelEngine,
    private val validator: MobileActionValidator = MobileActionValidator()
) {
    suspend fun route(prompt: String, onToken: (String) -> Unit = {}): RoutedAction {
        val generated = actionModel.generate(prompt, onToken)
        // Structured-output decoding is intentionally isolated here.
        return RoutedAction(
            generation = generated,
            validation = ActionValidation.Rejected("Action decoding is not wired yet.")
        )
    }

    data class RoutedAction(
        val generation: GenerationResult,
        val validation: ActionValidation
    )
}