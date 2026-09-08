package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** Drops unconfirmed playback echo, retains opening user audio, and owns exactly one collector. */
class BargeInAudioInput(
    private val input: AudioInput,
    private val createDetector: () -> SpeechDetector,
    private val playing: () -> Boolean,
    private val pauseProbe: (Boolean) -> Unit,
    private val discardQueued: () -> Unit = {},
    private val onConfirmed: () -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : AudioInput {
    override val sampleRateHz = input.sampleRateHz
    override val channelCount = input.channelCount
    override val lastChunkCaptureTimeMs: Long? get() = input.lastChunkCaptureTimeMs
    override val bufferedAudioMs: Long get() = input.bufferedAudioMs
    override suspend fun start() = input.start()
    override suspend fun stop() = input.stop()
    override fun chunks() = flow {
        val preRoll = RollingAudioBuffer(AudioFormat(sampleRateHz), maxDurationMs = 1200)
        val gate = BargeInGate()
        var detector = createDetector()
        var delivered = false
        try {
            input.chunks().collect { pcm ->
                if (delivered) { emit(pcm); return@collect }
                preRoll.append(pcm)
                val speech = detector.accept(pcm).isSpeech
                when (gate.update(speech, playing(), nowMs())) {
                    BargeInGate.Action.PAUSE -> {
                        pauseProbe(true)
                        discardQueued()
                        log("barge_probe_started")
                    }
                    BargeInGate.Action.RESET_DETECTOR -> {
                        detector.close(); detector = createDetector()
                        // Drop queued pre-pause echo before testing fresh microphone audio.
                        discardQueued()
                    }
                    BargeInGate.Action.CONFIRM -> {
                        delivered = true
                        onConfirmed()
                        log("barge_speech_confirmed preRollMs=${preRoll.sizeBytes() / 32}")
                        emit(preRoll.snapshot())
                        preRoll.clear()
                    }
                    BargeInGate.Action.RESUME -> { pauseProbe(false); preRoll.clear(); log("barge_probe_rejected_echo_or_noise") }
                    else -> Unit
                }
            }
        } finally { detector.close(); preRoll.clear(); pauseProbe(false) }
    }
}
