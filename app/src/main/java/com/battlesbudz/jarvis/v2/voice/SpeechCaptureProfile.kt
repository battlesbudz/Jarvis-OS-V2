package com.battlesbudz.jarvis.v2.voice

/** Microphone capture is hardwired to call noise reduction. There is no user setting. */
enum class SpeechCaptureProfile(
    val id: String,
    val label: String,
    val communicationInput: Boolean,
    val noiseSuppression: Boolean
) {
    COMMUNICATION_NOISE_FILTERED("communication_noise_filtered", "Call noise reduction", true, true);
}
