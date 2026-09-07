package com.battlesbudz.jarvis.v2.voice

data class AsrCaptureMetrics(
    val modelLoadMs: Long,
    val captureReadyMs: Long,
    val audioMs: Long,
    val decodeMs: Long,
    val maxDecodeChunkMs: Long,
    val firstPartialAfterSpeechMs: Long?,
    val partialUpdates: Int,
    val finalizationMs: Long,
    val endpointReason: String
)
