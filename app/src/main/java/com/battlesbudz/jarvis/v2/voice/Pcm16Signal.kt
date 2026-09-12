package com.battlesbudz.jarvis.v2.voice

import kotlin.math.abs
import kotlin.math.sqrt

/** PCM16 little-endian measurements for diagnostics, never speech classification. */
internal data class Pcm16Signal(
    val sampleCount: Int,
    val rms: Double,
    val peak: Int,
    val activeSampleRatio: Double
) {
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
