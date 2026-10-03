package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext

/** The voice owner and answer owner share this state under their existing single model lease. */
internal interface ConversationSessionState {
    var engine: LiteRtLmEngine?
    var hasContext: Boolean
    var characters: Int
}

/** Owns native conversation replacement, engine reuse, and persisted bounded summary updates. */
internal class ConversationModelSession(
    private val state: ConversationSessionState,
    val selectedModel: () -> LocalModelSpec,
    val operationActive: () -> Boolean,
    private val verifyModel: (LocalModelSpec) -> Boolean,
    private val modelPath: (LocalModelSpec) -> String,
    private val cachePath: String,
    private val shortTermContext: ShortTermConversationContext,
    private val persistSummary: (String?) -> Unit
) {
    var hasContext: Boolean
        get() = state.hasContext
        set(value) { state.hasContext = value }
    val characters get() = state.characters
    val residentBackend: ConversationBackend? get() = state.engine?.let(::LiteRtConversationBackend)

    fun verifyIntegrity(): Boolean = verifyModel(selectedModel())
    fun clearContextAccounting() { state.hasContext = false; state.characters = 0 }
    fun addCharacters(count: Int) { state.characters += count }
    fun clearSummary() { shortTermContext.clear() }
    fun close() { state.engine?.close(); state.engine = null; clearContextAccounting() }
    suspend fun reset() { state.engine?.resetConversation(); clearContextAccounting() }

    suspend fun prepare(imageAttached: Boolean, audioAttached: Boolean, voiceAttached: Boolean,
                        reply: ConversationReply): ConversationBackend {
        if (imageAttached) {
            check(selectedModel().supportsVision) {
                "${selectedModel().id} is text-only. Select a model with image input."
            }
            if (state.engine?.visionEnabled != true) close() else reset()
        }
        if (audioAttached) {
            check(selectedModel().supportsAudio) { "This download does not support audio input." }
            if (state.engine?.audioEnabled != true) close() else reset()
        }
        val started = System.nanoTime()
        reply.benchmark.mark("model_load_started")
        val wasLoaded = state.engine != null
        val engine = state.engine ?: LiteRtLmEngine(
            selectedModel().id, modelPath(selectedModel()), cachePath,
            useGpu = selectedModel().recommendedGpu,
            tools = if (selectedModel().supportsTools) MobileActionToolDefinitions.all() else emptyList(),
            visionEnabled = imageAttached && selectedModel().supportsVision,
            audioEnabled = (voiceAttached || audioAttached) && selectedModel().supportsAudio
        ).also { it.initialize(); state.engine = it; clearContextAccounting() }
        if (!wasLoaded) reply.loadMs += (System.nanoTime() - started) / 1_000_000
        reply.benchmark.mark("model_load_finished")
        reply.benchmark.metric("reply_model_load_ms", reply.loadMs)
        return LiteRtConversationBackend(engine)
    }

    suspend fun prepareHistory(history: List<ChatEntry>, memoryHistoryInvalidated: Boolean,
                               pendingRequestSize: Int, limit: Int): List<ChatEntry> {
        var promptHistory = if (memoryHistoryInvalidated) emptyList() else history
        if (state.characters + pendingRequestSize + ConversationPolicy.GENERATION_HEADROOM > limit) {
            val compacted = shortTermContext.compactSnapshot(promptHistory.map { it.role to it.text })
            if (compacted.isNotBlank()) {
                shortTermContext.updateSummary(compacted)
                persistSummary(shortTermContext.summaryForDiagnostics())
            }
            // The new summary contains recent turns; never seed them into native context twice.
            promptHistory = emptyList()
            reset()
        }
        return promptHistory
    }
}
