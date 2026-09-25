package com.battlesbudz.jarvis.v2.voice

/** External VAD owns the decoder's acoustic window, not only its first onset.
 * Preserve 240 ms before confirmed speech and 320 ms after it. Long quiet spans
 * stay out of the ungated native decoder; original call audio is retained separately.
 * Raw diagnostic recordings use native VAD; short final-only probes remain bounded.
 */
class ExternalSpeechGate {
    private val preRoll = RollingAudioBuffer(maxDurationMs = 240)
    private var observed = false
    private var speech = false
    private var tailBytes = 0
    var acceptedBytes = 0L
        private set
    var receivedBytes = 0L
        private set
    fun observe(speech: Boolean) { observed = true; this.speech = speech }
    fun accept(pcm: ByteArray): ByteArray {
        receivedBytes += pcm.size
        val result = when {
            !observed -> pcm
            speech -> {
                tailBytes = 320 * 32
                (preRoll.snapshot() + pcm).also { preRoll.clear() }
            }
            else -> {
                val count = minOf(tailBytes, pcm.size)
                tailBytes -= count
                if (count < pcm.size) preRoll.append(pcm.copyOfRange(count, pcm.size))
                pcm.copyOfRange(0, count)
            }
        }
        acceptedBytes += result.size
        return result
    }
    fun clear() { preRoll.clear(); observed = false; speech = false; tailBytes = 0 }
}
