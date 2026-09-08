package com.battlesbudz.jarvis.v2.voice

/** Keep precision, voice and model identity visible in every comparison. */
enum class TtsEngine(
    val id: String, val label: String, val directory: String, val modelFile: String,
    val speaker: Int, val archiveBytes: Long, val archiveSha256: String
) {
    KOKORO("kokoro", "Kokoro original", "kokoro-en-v0_19", "model.onnx", 10, 0, ""),
    PIPER("piper", "Piper Lessac (US)", "vits-piper-en_US-lessac-medium", "en_US-lessac-medium.onnx", 0,
        67230653, "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"),
    PIPER_ALAN("piper_alan", "Piper Alan (British)", "vits-piper-en_GB-alan-medium", "en_GB-alan-medium.onnx", 0,
        67220121, "a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012"),
    PIPER_RYAN("piper_ryan_high", "Piper Ryan High (US)", "vits-piper-en_US-ryan-high", "en_US-ryan-high.onnx", 0,
        115630708, "6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed");

    val isPiper: Boolean get() = this != KOKORO
    val modelBytes: Long get() = when (this) {
        KOKORO -> 0L
        PIPER -> 63149198L
        PIPER_ALAN -> 63201430L
        PIPER_RYAN -> 120786923L
    }
    val version: String get() = "$directory / speaker=$speaker / sherpa-1.13.7"
    val archiveUrl: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$directory.tar.bz2"
    companion object {
        // Historical diagnostics retain retired IDs rather than being relabeled as the fallback.
        fun diagnosticLabel(id: String) = entries.firstOrNull { it.id == id }?.label
            ?: if (id == "kokoro_int8") "Kokoro INT8 (retired)" else id.ifBlank { "Unknown voice" }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: KOKORO
    }
}
