package com.battlesbudz.jarvis.v2.ai

/** Native per-decode counters. Callback chunks and character estimates never enter this record. */
internal data class NativeTokenTelemetry(
    val inputTokens: Int,
    val outputTokens: Int,
    val timeToFirstTokenMs: Double?,
    val prefillTokensPerSecond: Double?,
    val decodeTokensPerSecond: Double?,
    val readMs: Double
) {
    companion object {
        fun checked(input: Int, output: Int, firstSeconds: Double, prefillRate: Double,
                    decodeRate: Double, readMs: Double): NativeTokenTelemetry? {
            if (input < 0 || output < 0 || !readMs.isFinite() || readMs < 0) return null
            fun rate(value: Double) = value.takeIf { it.isFinite() && it > 0 }
            return NativeTokenTelemetry(input, output,
                firstSeconds.takeIf { output > 0 && it.isFinite() && it >= 0 }?.times(1000),
                rate(prefillRate).takeIf { input > 0 }, rate(decodeRate).takeIf { output > 1 }, readMs)
        }
    }
}
