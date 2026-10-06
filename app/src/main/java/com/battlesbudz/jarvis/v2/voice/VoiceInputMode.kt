package com.battlesbudz.jarvis.v2.voice

import android.content.Context

/** Caption recognition is display-only in direct-audio mode, never request authorization. */
enum class VoiceInputMode(val id: String, val label: String) {
    TRANSCRIBED_TEXT("transcribed_text", "Speech recognition"),
    GEMMA_AUDIO("gemma_audio", "Gemma audio understanding");

    companion object {
        fun fromId(id: String?): VoiceInputMode = entries.firstOrNull { it.id == id } ?: TRANSCRIBED_TEXT
        fun selected(context: Context): VoiceInputMode = fromId(
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getString("input_mode", null))
        fun select(context: Context, mode: VoiceInputMode) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putString("input_mode", mode.id).apply()
        }
        /** Engine used for display-only captions in Gemma-audio mode. Captions are always on; there is no off setting. */
        fun captionEngine(context: Context): AsrEngine = AsrEngine.entries.firstOrNull {
            it.id == context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getString("caption_engine", null)
        } ?: AsrEngine.MOONSHINE
        fun captionEngine(context: Context, engine: AsrEngine) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putString("caption_engine", engine.id).apply()
        }
    }
}
