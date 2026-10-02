package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine

/**
 * Reuses the expensive engine while replacing only its bounded native conversation.
 * Called under the existing ConversationWork admission/model lease; this helper does not
 * acquire another lease or launch asynchronous native work.
 */
internal suspend fun JarvisRuntime.loadConversationEngine(
    imageAttached: Boolean, audioAttached: Boolean, voiceAttached: Boolean, reply: ConversationReply
): LiteRtLmEngine {
    // LiteRT-LM can retain a text-only native conversation, but
    // Gemma vision is reliable only when the image starts a fresh
    // native conversation. Keep the app transcript/history intact
    // and reseed that history into the fresh conversation below.
    if (imageAttached) {
        check(modelStore.selectedModel().supportsVision) {
            "${modelStore.selectedModel().id} is text-only. Select a model with image input."
        }
        if (conversationEngine?.visionEnabled != true) {
            conversationEngine?.close()
            conversationEngine = null
            nativeConversationHasContext = false
            conversationCharacters = 0
        } else resetNativeConversation()
    }

    if (audioAttached) {
        check(modelStore.selectedModel().supportsAudio) { "This download does not support audio input." }
        if (conversationEngine?.audioEnabled != true) {
            conversationEngine?.close()
            conversationEngine = null
            nativeConversationHasContext = false
            conversationCharacters = 0
        } else resetNativeConversation()
    }

    // Keep the expensive model/GPU engine alive. The replaceable
    // Conversation is reset only when the bounded context needs
    // to be compacted or an isolated retry is required.
    val loadingStarted = System.nanoTime()
    reply.benchmark.mark("model_load_started")
    val engineWasLoaded = conversationEngine != null
    val engine = conversationEngine ?: LiteRtLmEngine(
        modelStore.selectedModel().id,
        modelStore.fileFor(modelStore.selectedModel()).path,
        cacheDir.path,
        useGpu = modelStore.selectedModel().recommendedGpu,
        tools = if (modelStore.selectedModel().supportsTools)
            com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions.all() else emptyList(),
        visionEnabled = imageAttached && modelStore.selectedModel().supportsVision,
        audioEnabled = (voiceAttached || audioAttached) && modelStore.selectedModel().supportsAudio
    ).also {
        it.initialize()
        conversationEngine = it
        nativeConversationHasContext = false
        conversationCharacters = 0
    }
    if (!engineWasLoaded) reply.loadMs += (System.nanoTime() - loadingStarted) / 1_000_000
    reply.benchmark.mark("model_load_finished")
    reply.benchmark.metric("reply_model_load_ms", reply.loadMs)
    return engine
}

/** Compaction resets bounded native context, while keeping the visible transcript intact. */
internal suspend fun JarvisRuntime.prepareConversationHistory(
    history: List<ChatEntry>, memoryHistoryInvalidated: Boolean, pendingRequestSize: Int, limit: Int
): List<ChatEntry> {
    var promptHistory = if (memoryHistoryInvalidated) emptyList() else history
    if (conversationCharacters + pendingRequestSize + ConversationPolicy.GENERATION_HEADROOM > limit) {
        val compactedText = shortTermContext.compactSnapshot(promptHistory.map { it.role to it.text })
        if (compactedText.isNotBlank()) {
            shortTermContext.updateSummary(compactedText)
            sessionPreferences.edit()
                .putString(ConversationPolicy.SHORT_TERM_SUMMARY_KEY, shortTermContext.summaryForDiagnostics())
                .apply()
        }
        // The compacted summary already contains the newest turns; do not seed them twice.
        promptHistory = emptyList()
        resetNativeConversation()
    }
    return promptHistory
}
