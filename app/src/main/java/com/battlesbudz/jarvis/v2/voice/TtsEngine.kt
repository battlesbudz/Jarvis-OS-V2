package com.battlesbudz.jarvis.v2.voice

/** Keep precision, voice and model identity visible in every comparison. */
enum class TtsEngine(
    val id: String, val label: String, val directory: String, val modelFile: String,
    val speaker: Int, val archiveBytes: Long, val archiveSha256: String
) {
    KOKORO("kokoro", "Kokoro original", "kokoro-en-v0_19", "model.onnx", 10, 0, ""),
    PIPER_MIRO("piper_miro_high", "Piper Miro High (British)", "vits-piper-en_GB-miro-high", "en_GB-miro-high.onnx", 0,
        67194499, "c42907615e1a95bb69a85674f9d7979eeb2352c40fe86a0ae84d5479149c03d1"),
    POCKET_PAUL("pocket_paul", "Pocket TTS — Paul", "sherpa-onnx-pocket-tts-int8-2026-01-26", "lm_main.int8.onnx", 0,
        98336520, "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb");

    val isPiper: Boolean get() = this == PIPER_MIRO
    val modelBytes: Long get() = when (this) {
        KOKORO -> 0L
        PIPER_MIRO -> 63511174L
        POCKET_PAUL -> 76341079L
    }
    val version: String get() = "$directory / speaker=${if (this == POCKET_PAUL) "Paul-p259" else speaker} / sherpa-1.13.7" +
        if (this == POCKET_PAUL) " / ${PocketSpeechPolicy.VERSION}" else ""
    val archiveUrl: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$directory.tar.bz2"
    companion object {
        // Historical diagnostics retain retired IDs rather than being relabeled as the fallback.
        fun diagnosticLabel(id: String) = entries.firstOrNull { it.id == id }?.label
            ?: when (id) {
                "kokoro_int8" -> "Kokoro INT8 (retired)"
                "piper" -> "Piper Lessac (US) (retired)"
                "piper_alan" -> "Piper Alan (British) (retired)"
                "piper_ryan_high" -> "Piper Ryan High (US) (retired)"
                else -> id.ifBlank { "Unknown voice" }
            }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: KOKORO
    }
}
