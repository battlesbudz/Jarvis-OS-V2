package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SherpaTtsConfigTest {
    @Test fun pocketUsesAllSevenModelsWithoutPiperOrKokoroFallback() {
        val config = sherpaTtsConfig(TtsEngine.POCKET_PAUL, "/models/paul", 2)
        assertEquals(2, config.model.numThreads)
        assertEquals("cpu", config.model.provider)
        val pocket = config.model.pocket
        assertEquals(listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx",
            "text_conditioner.onnx", "vocab.json", "token_scores.json").map { "/models/paul/$it" },
            listOf(pocket.lmFlow, pocket.lmMain, pocket.encoder, pocket.decoder,
                pocket.textConditioner, pocket.vocabJson, pocket.tokenScoresJson))
        assertEquals(1, pocket.voiceEmbeddingCacheCapacity)
        assertTrue(config.model.vits.model.isEmpty())
        assertTrue(config.model.kokoro.model.isEmpty())
    }
    @Test fun existingVoicesKeepTheirOwnModelFamilies() {
        val kokoro = sherpaTtsConfig(TtsEngine.KOKORO, "/models/kokoro", 4)
        assertTrue(kokoro.model.kokoro.model.isNotEmpty())
        assertTrue(kokoro.model.pocket.lmMain.isEmpty())
    }
}
