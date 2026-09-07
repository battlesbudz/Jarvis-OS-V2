package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

object VoiceCallPolicy {
    const val TURN_SILENCE_MS = 3_000L
    const val CALL_INACTIVITY_MS = 20_000L
    const val ENDED_PREFIX = "Voice Call ended:"

    /** Whole utterances only: a story mentioning goodbye is not a hang-up command. */
    fun isGoodbye(transcript: String): Boolean {
        val words = transcript.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().replace(Regex("\\s+"), " ")
        return words in setOf("goodbye", "goodbye jarvis", "jarvis goodbye",
            "stop listening", "stop listening jarvis", "jarvis stop listening")
    }
}
