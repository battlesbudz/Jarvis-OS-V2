package com.battlesbudz.jarvis.v2.ai

import java.io.Closeable

/**
 * Owns both LiteRT-LM engines so model initialization and cleanup are explicit.
 */
class LocalModelPair(
    val primary: LiteRtLmEngine,
    val actionModel: LiteRtLmEngine
) : Closeable {
    suspend fun initialize() {
        primary.initialize()
        actionModel.initialize()
    }

    override fun close() {
        actionModel.close()
        primary.close()
    }
}