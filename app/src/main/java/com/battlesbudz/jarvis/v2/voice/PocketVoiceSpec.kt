package com.battlesbudz.jarvis.v2.voice

/** Pinned published Pocket model and Kyutai's official Paul reference (VCTK p259). */
internal object PocketVoiceSpec {
    const val PAUL_FILE = "paul.wav"
    const val PAUL_BYTES = 717182L
    const val PAUL_SHA256 = "7aba504fe0b3b16478b69eb27ce6007e3cb42b0c1915b5f1c6a6024ae37d679b"
    const val PAUL_URL = "https://huggingface.co/kyutai/tts-voices/resolve/a0de156151266cf8eb27ac8f27312f7aff2ef7b8/vctk/p259_023_enhanced.wav"
    val files = mapOf("lm_flow.int8.onnx" to 9962530L, "lm_main.int8.onnx" to 76341079L,
        "encoder.onnx" to 72713165L, "decoder.int8.onnx" to 22693618L,
        "text_conditioner.onnx" to 16388343L, "vocab.json" to 69478L,
        "token_scores.json" to 123616L, "LICENSE" to 18655L)
}
