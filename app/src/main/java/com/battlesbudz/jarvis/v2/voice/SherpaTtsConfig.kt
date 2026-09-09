package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.*
import java.io.File

internal fun sherpaTtsConfig(engine: TtsEngine, directory: String, threads: Int): OfflineTtsConfig {
    val model = OfflineTtsModelConfig(numThreads = threads, debug = false, provider = "cpu")
    if (engine == TtsEngine.POCKET_PAUL) {
        model.pocket = OfflineTtsPocketModelConfig(
            lmFlow = "$directory/lm_flow.int8.onnx", lmMain = "$directory/lm_main.int8.onnx",
            encoder = "$directory/encoder.onnx", decoder = "$directory/decoder.int8.onnx",
            textConditioner = "$directory/text_conditioner.onnx", vocabJson = "$directory/vocab.json",
            tokenScoresJson = "$directory/token_scores.json", voiceEmbeddingCacheCapacity = 1)
    } else {
        model.kokoro = OfflineTtsKokoroModelConfig(
            model = "$directory/${engine.modelFile}", voices = "$directory/voices.bin",
            tokens = "$directory/tokens.txt", dataDir = "$directory/espeak-ng-data",
            lexicon = File(directory, "lexicon-us-en.txt").takeIf { it.isFile }?.path.orEmpty(), lang = "en-us"
        )
    }
    return OfflineTtsConfig(model = model, maxNumSentences = 1, silenceScale = 0.2f)
}
