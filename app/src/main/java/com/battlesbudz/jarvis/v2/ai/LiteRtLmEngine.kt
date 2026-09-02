package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import java.io.Closeable

/**
 * Real Android adapter for a .litertlm model.
 *
 * Initialization and generation must be called from a background coroutine.
 * The model file is supplied by setup/model delivery and is never committed
 * to the repository.
 */
class LiteRtLmEngine(
    override val modelId: String,
    modelPath: String,
    cacheDir: String,
    useGpu: Boolean
) : LocalModelEngine, Closeable {
    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            cacheDir = cacheDir,
            backend = if (useGpu) Backend.GPU() else Backend.CPU()
        )
    )
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    suspend fun initialize() {
        engine.initialize()
        conversation = engine.createConversation()
    }

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): GenerationResult {
        val activeConversation = requireNotNull(conversation) {
            "LiteRT-LM engine must be initialized before generation."
        }
        val startedAt = System.nanoTime()
        var firstTokenAt: Long? = null
        val output = StringBuilder()

        activeConversation.sendMessageAsync(prompt).collect { message ->
            val text = message.toString()
            if (text.isNotEmpty()) {
                firstTokenAt = firstTokenAt ?: System.nanoTime()
                output.append(text)
                onToken(text)
            }
        }

        val firstTokenMs = firstTokenAt?.let { (it - startedAt) / 1_000_000 } ?: -1L
        return GenerationResult(
            text = output.toString(),
            timeToFirstTokenMs = firstTokenMs,
            decodeTokensPerSecond = null
        )
    }

    override fun close() {
        conversation?.close()
        engine.close()
    }
}
