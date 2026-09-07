package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.Flow

/**
 * Converts microphone audio into bounded user turns. An ASR engine may be
 * inserted here later; JarvisBrain does not depend on that implementation.
 */
interface SpeechPipeline {
    val turns: Flow<VoiceTurn>
    suspend fun start()
    suspend fun stop()
}

data class VoiceTurn(
    val audioWav: ByteArray,
    val transcript: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long = System.currentTimeMillis()
)

interface VoiceOutput {
    suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit = {})
    fun stopSpeaking()
}

