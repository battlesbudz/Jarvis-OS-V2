package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicLong

/** One VAD producer, one ASR consumer. PCM is ordered, bounded, and never conflated. */
internal class CaptureSpeechQueue(
    private val input: AudioInput,
    private val detector: SpeechDetector,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val maxBytes: Long = 25 * 32_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    data class Frame(val pcm: ByteArray, val decision: SpeechDecision, val capturedAtMs: Long, val sequence: Long?)
    init { input.deferConsumptionAcknowledgement() }
    private val pendingBytes = AtomicLong()
    val bufferedAudioMs: Long get() = pendingBytes.get() / 32 + input.bufferedAudioMs
    @Volatile var targetSilenceMs: Long = 3000
    @Volatile var latestSilenceDetectedAtMs: Long? = null
        private set
    @Volatile var latestSpeechAtMs: Long? = null
        private set

    fun frames(): Flow<Frame> = flow {
        val gate = CaptureSpeechGate()
        var lastNoiseLogAt: Long? = null
        input.chunks().collect { pcm ->
            check(pendingBytes.addAndGet(pcm.size.toLong()) <= maxBytes) {
                "Recognition queue exceeded 25 seconds; incomplete command must not be submitted"
            }
            val capturedAt = input.lastChunkCaptureTimeMs ?: nowMs()
            val raw = detector.accept(pcm)
            val signal = Pcm16Signal.measure(pcm)
            val decision = gate.accept(raw, signal.rms, capturedAt)
            if (raw.probability >= 0.15f && decision.probability == 0f &&
                (lastNoiseLogAt == null || capturedAt - lastNoiseLogAt!! >= 1000)) {
                lastNoiseLogAt = capturedAt
                log("capture_weak_noise_rejected probability=${raw.probability} rms=${signal.rms.toInt()} noiseFloorRms=${gate.noiseFloorRms.toInt()}")
            }
            if (decision.isSpeech) {
                latestSpeechAtMs = capturedAt
                latestSilenceDetectedAtMs = null
            } else {
                val speechAt = latestSpeechAtMs
                if (speechAt != null && latestSilenceDetectedAtMs == null &&
                    capturedAt - speechAt >= targetSilenceMs) {
                    latestSilenceDetectedAtMs = nowMs()
                    log("capture_silence_detected silenceAudioMs=${capturedAt - speechAt} " +
                        "detectorLagMs=${(nowMs() - capturedAt).coerceAtLeast(0)} " +
                        "recognitionBacklogMs=$bufferedAudioMs targetSilenceMs=$targetSilenceMs")
                }
            }
            emit(Frame(pcm, decision, capturedAt, input.lastChunkSequence))
        }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    fun consumed(frame: Frame) {
        pendingBytes.addAndGet(-frame.pcm.size.toLong())
        frame.sequence?.let(input::acknowledgeConsumed)
    }
}
