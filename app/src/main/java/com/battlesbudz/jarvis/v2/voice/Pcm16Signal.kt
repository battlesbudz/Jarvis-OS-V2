package com.battlesbudz.jarvis.v2.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** Shared PCM16 little-endian measurements for endpointing and diagnostics. */
internal data class Pcm16Signal(
    val sampleCount: Int,
    val rms: Double,
    val peak: Int,
    val activeSampleRatio: Double
) {
    val isSpeech: Boolean
        get() = rms >= 500.0 || (peak >= 1_400 && activeSampleRatio >= 0.01)
    val isLikelySilence: Boolean
        get() = rms < 220.0 || (peak < 700 && activeSampleRatio < 0.01)

    companion object {
        fun measure(bytes: ByteArray, start: Int = 0): Pcm16Signal {
            var sumSquares = 0.0
            var peak = 0
            var active = 0
            var count = 0
            var offset = start
            while (offset + 1 < bytes.size) {
                // Mask both bytes before interpreting the combined word as signed.
                // Sign-extending the high byte and then subtracting 65536 again
                // turns -1 (quiet noise) into -65537 (impossibly loud speech).
                val word = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8)
                val sample = word.toShort().toInt()
                val magnitude = abs(sample)
                sumSquares += sample.toDouble() * sample.toDouble()
                peak = maxOf(peak, magnitude)
                if (magnitude >= 500) active++
                count++
                offset += 2
            }
            return if (count == 0) Pcm16Signal(0, 0.0, 0, 0.0)
            else Pcm16Signal(count, sqrt(sumSquares / count), peak, active.toDouble() / count)
        }
    }
}
