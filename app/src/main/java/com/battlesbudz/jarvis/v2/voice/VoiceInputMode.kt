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
        fun captions(context: Context): Boolean = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
            .getBoolean("gemma_whisper_captions", true)
        fun captions(context: Context, enabled: Boolean) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putBoolean("gemma_whisper_captions", enabled).apply()
        }
    }
}

