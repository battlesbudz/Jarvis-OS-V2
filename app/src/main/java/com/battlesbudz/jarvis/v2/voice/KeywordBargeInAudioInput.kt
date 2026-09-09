package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

interface InterruptionKeywordDetector : AutoCloseable {
    val ready: Boolean
    fun accept(pcm: ByteArray): String?
}

/** No ASR work before a keyword. Keep the same microphone for the following request. */
class KeywordBargeInAudioInput(
    private val input: AudioInput,
    private val createDetector: () -> InterruptionKeywordDetector,
    private val onConfirmed: (String) -> Unit,
    private val log: (String) -> Unit = {}
) : AudioInput {
    override val sampleRateHz get() = input.sampleRateHz
    override val channelCount get() = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        var delivered = false
        var readyReported = false
        val detector = createDetector()
        try {
            input.chunks().collect { pcm ->
                if (delivered) { emit(pcm); return@collect }
                val keyword = detector.accept(pcm)
                if (detector.ready && !readyReported) {
                    readyReported = true
                    log("barge_keyword_ready keywords=Hey_Jarvis,stop asrLoaded=false")
                }
                if (keyword != null) {
                    delivered = true
                    // Stop playback before lazily loading ASR, preserving subsequent chunks.
                    onConfirmed(keyword)
                    log("barge_keyword_confirmed keyword=$keyword")
                    // Consume the trigger itself: it must not become a new "stop listening"
                    // command or carry the reply's echo into the correction transcript.
                    // The user can speak the new request after playback stops.
                }
            }
        } finally { detector.close() }
    }
}
