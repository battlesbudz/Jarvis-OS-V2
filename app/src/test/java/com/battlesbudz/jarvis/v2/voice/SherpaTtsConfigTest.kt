package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SherpaTtsConfigTest {
    @Test fun northernPiperUsesVitsAndPersistsItsOwnIdentity() {
        val engine = TtsEngine.PIPER_NORTHERN
        val config = sherpaTtsConfig(engine, "/models/northern", 2)
        assertEquals(engine, TtsEngine.fromId(engine.id))
        assertEquals("/models/northern/en_GB-northern_english_male-medium.onnx", config.model.vits.model)
        assertEquals("/models/northern/tokens.txt", config.model.vits.tokens)
        assertEquals("/models/northern/espeak-ng-data", config.model.vits.dataDir)
        assertTrue(config.model.kokoro.model.isEmpty())
        assertTrue(config.model.pocket.lmMain.isEmpty())
        assertEquals(engine.modelBytes, NorthernPiperSpec.files[engine.modelFile])
        assertTrue(NorthernPiperSpec.files.containsKey("MODEL_CARD"))
        assertTrue(NorthernPiperSpec.files.keys.any { it.startsWith("espeak-ng-data/") })
    }

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
