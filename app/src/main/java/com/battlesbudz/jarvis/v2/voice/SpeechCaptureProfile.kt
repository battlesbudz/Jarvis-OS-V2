package com.battlesbudz.jarvis.v2.voice

import android.content.Context

/** Capture source/effects are independent of the duplex speaker/focus route.
 * Preserve phonetic input by default; retain AEC through the caller's existing
 * echoCancellation request. The communication profile is an explicit fallback,
 * not evidence that either profile has lower WER on a particular phone. */
enum class SpeechCaptureProfile(
    val id: String,
    val label: String,
    val communicationInput: Boolean,
    val noiseSuppression: Boolean
) {
    SPEECH_PRESERVING("speech_preserving", "Speech clarity", false, false),
    COMMUNICATION_NOISE_FILTERED("communication_noise_filtered", "Call noise reduction", true, true);

    companion object {
        const val PREFERENCES = "voice_input"
        const val KEY = "capture_profile"
        fun fromId(id: String?): SpeechCaptureProfile = entries.firstOrNull { it.id == id } ?: SPEECH_PRESERVING
        fun selected(context: Context): SpeechCaptureProfile = fromId(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))
        fun select(context: Context, profile: SpeechCaptureProfile) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, profile.id).apply()
        }
    }
}
