package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import java.io.File

enum class AsrEngine(val id: String, val label: String, val modelVersion: String) {
    MOONSHINE(MoonshineModelInfo.id, "Moonshine", MoonshineModelInfo.modelVersion),
    WHISPER("whisper_base_en", "Whisper base.en", "base.en-int8 / sherpa-1.13.7");

    fun create(directory: File, live: Boolean = true, log: (String) -> Unit = {}, modelSession: VoiceModelSession? = null): StreamingTranscriber = when (this) {
        MOONSHINE -> MoonshineStreamingTranscriber(directory, modelSession = modelSession)
        WHISPER -> WhisperTranscriber(directory, live, log, modelSession)
    }
    suspend fun prepare(context: Context, status: (String) -> Unit): File = when (this) {
        MOONSHINE -> AsrModelStore(context).ensureReady(status)
        WHISPER -> RecognitionModelStore(context).whisper(status)
    }
    companion object {
        fun selected(context: Context): AsrEngine = entries.firstOrNull {
            it.id == context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getString("engine", null)
        } ?: WHISPER
        fun select(context: Context, engine: AsrEngine) {
            context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).edit().putString("engine", engine.id).apply()
        }
    }
}
