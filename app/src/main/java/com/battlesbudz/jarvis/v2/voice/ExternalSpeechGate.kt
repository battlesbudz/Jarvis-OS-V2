package com.battlesbudz.jarvis.v2.voice

/** Jarvis VAD owns live onset; retain consonants before confirmation, then the whole utterance.
 * Callers without live VAD (same-recording diagnostics) supply an already bounded recording.
 */
class ExternalSpeechGate {
    private val preRoll = RollingAudioBuffer(maxDurationMs = 1200)
    private var observed = false
    private var opened = false
    fun observe(speech: Boolean) { observed = true; if (speech) opened = true }
    fun accept(pcm: ByteArray): ByteArray {
        if (!observed) return pcm
        if (!opened) { preRoll.append(pcm); return byteArrayOf() }
        if (preRoll.sizeBytes() == 0L) return pcm
        return (preRoll.snapshot() + pcm).also { preRoll.clear() }
    }
    fun clear() = preRoll.clear()
}
