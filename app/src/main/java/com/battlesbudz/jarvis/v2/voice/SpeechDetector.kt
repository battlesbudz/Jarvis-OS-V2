package com.battlesbudz.jarvis.v2.voice

/** Classification of audio content; loudness is only diagnostic information. */
data class SpeechDecision(val isSpeech: Boolean, val probability: Float)

interface SpeechDetector : AutoCloseable {
    fun accept(pcm: ByteArray): SpeechDecision
}

/** Frames arbitrary PCM16 chunks for Silero and rejects isolated positive frames. */
class FrameSpeechDetector(
    private val computeProbability: (FloatArray) -> Float,
    private val releaseModel: () -> Unit = {}
) : SpeechDetector {
    private val frame = FloatArray(512)
    private var frameSize = 0
    private var lowByte: Int? = null
    private var consecutiveSpeechFrames = 0
    private var lastProbability = 0f
    private var closed = false

    override fun accept(pcm: ByteArray): SpeechDecision {
        check(!closed) { "Speech detector has been released." }
        var confirmedSpeech = false
        var maxProbability = 0f
        var computed = false
        for (byte in pcm) {
            val low = lowByte
            if (low == null) {
                lowByte = byte.toInt() and 0xff
                continue
            }
            val word = low or ((byte.toInt() and 0xff) shl 8)
            frame[frameSize++] = word.toShort().toInt() / 32768f
            lowByte = null
            if (frameSize == frame.size) {
                val probability = computeProbability(frame)
                check(probability.isFinite() && probability in 0f..1f) {
                    "Speech detector returned an invalid probability."
                }
                lastProbability = probability
                maxProbability = maxOf(maxProbability, probability)
                computed = true
                consecutiveSpeechFrames = if (probability >= 0.5f) {
                    (consecutiveSpeechFrames + 1).coerceAtMost(3)
                } else 0
                // Three 32 ms frames confirm speech. Pre-roll preserves onset.
                confirmedSpeech = confirmedSpeech || consecutiveSpeechFrames >= 3
                frameSize = 0
            }
        }
        return SpeechDecision(confirmedSpeech, if (computed) maxProbability else lastProbability)
    }

    override fun close() {
        if (!closed) {
            closed = true
            releaseModel()
        }
    }
}
