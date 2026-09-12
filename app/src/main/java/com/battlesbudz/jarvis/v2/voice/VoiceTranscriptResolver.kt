package com.battlesbudz.jarvis.v2.voice

/** ASR assists the audio model; an empty ASR result never vetoes confirmed speech. */
object VoiceTranscriptResolver {
    const val UNTRANSCRIBED = "[Voice message — transcription unavailable]"
    val instructions = """
        Transcribe only the words spoken in the attached audio. Do not answer the
        request or call tools. Do not infer words from prior conversation.
        Return only the transcript without commentary. If no intelligible words
        are audible, return exactly [NO_SPEECH].
    """.trimIndent()

    suspend fun resolve(asr: String, wav: ByteArray, hearAudio: suspend (ByteArray) -> String): String {
        if (asr.isNotBlank()) return asr.trim()
        val heard = hearAudio(wav).trim()
        // Preserve a truthful transcript placeholder; the response pass still
        // receives the original audio and can ask for clarification. This label
        // cannot authorize a tool through the existing final-transcript guard.
        return heard.takeUnless { it.isBlank() || it == "[NO_SPEECH]" } ?: UNTRANSCRIBED
    }
}
