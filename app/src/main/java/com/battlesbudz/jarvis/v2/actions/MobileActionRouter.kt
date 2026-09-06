package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.ai.LocalModelEngine

/**
 * Legacy text-generation adapter retained for callers that only expose the
 * LocalModelEngine interface. The live PR1 path uses LiteRtLmEngine's
 * structured tool-call API and FunctionGemmaActionDecoder instead.
 * Generated text is never executed directly.
 */
class MobileActionRouter(
    private val actionModel: LocalModelEngine,
    private val validator: MobileActionValidator = MobileActionValidator()
) {
    suspend fun route(prompt: String, onToken: (String) -> Unit = {}): RoutedAction {
        val generated = actionModel.generate(prompt, onToken)
        // Structured-output decoding requires the LiteRT-LM Conversation tool
        // calls, so this compatibility adapter must not guess from free text.
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