package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** One microphone and one temporary ASR owner. Playback is never paused for a probe. */
class BargeInAudioInput(
    private val input: AudioInput,
    private val createDetector: () -> SpeechDetector,
    private val playing: () -> Boolean,
    private val createTranscriber: () -> StreamingTranscriber,
    private val spokenText: () -> String,
    private val onConfirmed: () -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : AudioInput {
    override val sampleRateHz = input.sampleRateHz
    override val channelCount = input.channelCount
    override val lastChunkCaptureTimeMs get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        val preRoll = RollingAudioBuffer(AudioFormat(sampleRateHz), maxDurationMs = 3000)
        var gate = BargeInGate()
        val detector = createDetector()
        val quietEvidence = QuietSpeechEvidence()
        var recognizer: StreamingTranscriber? = null
        var recognizedBytes = 0
        var delivered = false
        try {
            input.chunks().collect { pcm ->
                if (delivered) { emit(pcm); return@collect }
                preRoll.append(pcm)
                val decision = detector.accept(pcm)
                val audible = playing()
                // Quiet generation needs only acoustic confirmation. During playback,
                // corroborate speech with stable words that are not Jarvis's own reply.
                val transcript = if (audible) {
                    val asr = recognizer ?: createTranscriber().also { recognizer = it }
                    recognizedBytes += pcm.size
                    asr.accept(pcm)
                } else ""
                val speech = decision.isSpeech || quietEvidence.accept(transcript, decision.probability, nowMs(), false)
                if (gate.update(speech, audible, nowMs(), transcript, spokenText()) == BargeInGate.Action.CONFIRM) {
                    // Close the probe ASR before the turn's lazy ASR can load: never two models.
                    recognizer?.close(); recognizer = null
                    delivered = true
                    onConfirmed()
                    log("barge_speech_confirmed method=${if (audible) "new_words" else "acoustic"} preRollMs=${preRoll.sizeBytes() / 32}")
                    emit(preRoll.snapshot()); preRoll.clear()
                } else if (recognizedBytes >= 16_000 * 2 * 10) {
                    recognizer?.close(); recognizer = null
                    recognizedBytes = 0; gate = BargeInGate(); quietEvidence.reset()
                    log("barge_echo_window_reset playback_uninterrupted=true")
                }
            }
        } finally { recognizer?.close(); detector.close(); preRoll.clear() }
    }
}

/** Delay the final-turn ASR until the interruption recognizer has released its model. */
class LazyStreamingTranscriber(private val create: () -> StreamingTranscriber) : StreamingTranscriber {
    private var delegate: StreamingTranscriber? = null
    private fun active() = delegate ?: create().also { delegate = it }
    override fun accept(pcm: ByteArray) = active().accept(pcm)
    override fun finish() = delegate?.finish().orEmpty()
    override fun recover(pcm: ByteArray) = active().recover(pcm)
    override fun close() { delegate?.close(); delegate = null }
}
