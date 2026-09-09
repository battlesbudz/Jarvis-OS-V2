package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.ai.GemmaBenchmarkStore

class VoiceLatencyBenchmarkActions(
    val gemmaResults: GemmaBenchmarkStore,
    val compareGemma: ((String) -> Unit, () -> Unit) -> Unit,
    val compareOpenings: (TtsEngine, (String) -> Unit, () -> Unit) -> Unit,
    val comparePaulIsolation: ((String) -> Unit, () -> Unit) -> Unit,
    val stop: () -> Unit
)
