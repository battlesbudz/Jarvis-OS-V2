package com.battlesbudz.jarvis.v2.voice

/** Keep precision, voice and model identity visible in every comparison. */
enum class TtsEngine(
    val id: String, val label: String, val directory: String, val modelFile: String,
    val speaker: Int, val archiveBytes: Long, val archiveSha256: String
) {
    KOKORO("kokoro", "Kokoro original", "kokoro-en-v0_19", "model.onnx", 10, 0, ""),
    KOKORO_INT8("kokoro_int8", "Kokoro INT8", "kokoro-int8-en-v0_19", "model.int8.onnx", 10,
        103248205, "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"),
    PIPER("piper", "Piper Lessac", "vits-piper-en_US-lessac-medium", "en_US-lessac-medium.onnx", 0,
        67230653, "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e");

    val modelBytes: Long get() = when (this) {
        KOKORO -> 0L
        KOKORO_INT8 -> 134186977L
        PIPER -> 63149198L
    }
    val version: String get() = "$directory / speaker=$speaker / sherpa-1.13.7"
    val archiveUrl: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$directory.tar.bz2"
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: KOKORO
    }
}
