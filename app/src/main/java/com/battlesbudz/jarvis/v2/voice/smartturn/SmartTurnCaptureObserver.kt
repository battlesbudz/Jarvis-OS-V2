package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.voice.CaptureShadowFrame
import com.battlesbudz.jarvis.v2.voice.CaptureShadowObserver

internal interface SmartTurnTelemetry {
    fun metric(name: String, value: Number?)
    fun configuration(name: String, value: String)
    fun event(message: String)
    fun completion(index: Int, completion: SmartTurnWorkCompletion, postPriorityWorkerMs: Double?) {}
}

/** Diagnostics are never a dependency for revocation or ordinary voice availability. */
internal class SafeSmartTurnTelemetry(private val delegate: SmartTurnTelemetry) : SmartTurnTelemetry {
    override fun metric(name: String, value: Number?) { runCatching { delegate.metric(name, value) } }
    override fun configuration(name: String, value: String) { runCatching { delegate.configuration(name, value) } }
    override fun event(message: String) { runCatching { delegate.event(message) } }
    override fun completion(index: Int, completion: SmartTurnWorkCompletion, postPriorityWorkerMs: Double?) {
        runCatching { delegate.completion(index, completion, postPriorityWorkerMs) }
    }
}

/** A bounded observation lane: no return value can alter capture or an endpoint decision. */
internal class SmartTurnCaptureObserver(
    private val shadow: SmartTurnShadow,
    turnId: String,
    captureGeneration: Long,
    telemetry: SmartTurnTelemetry,
    private val ownerIsCurrent: () -> Boolean,
    private val admissionBlocker: () -> String?,
    private val clock: () -> Long = System::nanoTime,
) : CaptureShadowObserver {
    private val evidence = SafeSmartTurnTelemetry(telemetry)
    private var generation = SmartTurnGeneration(turnId, captureGeneration, 0)
    private val ring = ShortArray(SmartTurnAudioSnapshot.MAX_SAMPLES)
    private var size = 0
    private var next = 0
    private var boundary = 0L
    private var hasBoundary = false
    private var closed = false
    private var prioritized = false
    private var possibleSpeech = true
    private var attempts = 0
    private var accepted = 0
    private var busy = 0
    private var blocked = 0
    private var resumed = 0
    private var lastAttemptAt: Long? = null
    private var priorityAt: Long? = null
    private val results = mutableMapOf<Long, Int>()
    private val observedAt = mutableMapOf<Int, Long>()
    private val resumedObservations = mutableSetOf<Int>() // At most three accepted observation identities.

    init {
        shadow.updateGeneration(generation)
        evidence.configuration("smart_turn_mode", "shadow_only_v1")
        evidence.configuration("smart_turn_model_sha256", SmartTurnModelSpec.SHA256)
        evidence.configuration("smart_turn_budget", "attempts=3,spacing_ms=500,pause_ms=200,deadline_ms=250,window_samples=128000,threads=1")
        evidence.configuration("smart_turn_contention", "background_priority_requested;cancel_on_native_priority;overlap_may_remain_until_drain")
    }

    @Synchronized override fun onPcm(pcm16: ByteArray, captureSampleBoundary: Long) {
        if (closed || prioritized) return
        if (!runCatching(ownerIsCurrent).getOrDefault(false)) { close(); return }
        if (pcm16.isEmpty() || pcm16.size % 2 != 0 || captureSampleBoundary < pcm16.size / 2 ||
            (hasBoundary && captureSampleBoundary - boundary != (pcm16.size / 2).toLong())) {
            onPriority("pcm_discontinuity")
            return
        }
        // Copy at most eight seconds, never retain the producer array or earlier audio.
        val count = minOf(pcm16.size / 2, ring.size)
        val offset = pcm16.size / 2 - count
        repeat(count) { index ->
            val position = (index + offset) * 2
            ring[next] = ((pcm16[position].toInt() and 255) or (pcm16[position + 1].toInt() shl 8)).toShort()
            next = (next + 1) % ring.size
        }
        size = minOf(ring.size, size + count)
        boundary = captureSampleBoundary
        hasBoundary = true
    }

    @Synchronized override fun onFrame(frame: CaptureShadowFrame) {
        if (closed) return
        if (!runCatching(ownerIsCurrent).getOrDefault(false)) { close(); return }
        if (frame.possibleSpeech && !possibleSpeech) {
            resumed++
            generation = generation.copy(revision = generation.revision + 1)
            shadow.updateGeneration(generation)
            evidence.metric("smart_turn_resumption_invalidations", resumed)
            observedAt.forEach { (index, capturedAt) ->
                if (resumedObservations.add(index)) {
                    evidence.metric("smart_turn_${index}_speech_resumed_after_observation", 1)
                    evidence.metric("smart_turn_${index}_resumption_after_ms", (frame.capturedAtNanos - capturedAt).coerceAtLeast(0) / 1e6)
                }
            }
        }
        possibleSpeech = frame.possibleSpeech
        if (prioritized) return
        drainResult()
        if (!frame.hasSpeech || frame.possibleSpeech || frame.silenceMs < 200 || size < 1600 || attempts >= 3) return
        val now = clock()
        if (lastAttemptAt?.let { now - it < 500_000_000L } == true) return
        lastAttemptAt = now
        attempts++
        evidence.metric("smart_turn_observation_attempts", attempts)
        val blocker = runCatching(admissionBlocker).getOrDefault("admission_unavailable") ?: if (frame.backlogMs > 40) "capture_backlog" else null
        if (blocker != null) {
            blocked++
            evidence.metric("smart_turn_priority_or_backlog_skips", blocked)
            evidence.configuration("smart_turn_last_admission_blocker", blocker)
            return
        }
        if (shadow.isBusy()) {
            busy++; evidence.metric("smart_turn_busy_skips", busy); return
        }
        if (boundary != frame.captureSampleBoundary) {
            evidence.configuration("smart_turn_last_admission_blocker", "retained_pcm_boundary_mismatch")
            return
        }
        val pcm = ShortArray(size) { ring[(next - size + it + ring.size) % ring.size] }
        val snapshot = SmartTurnAudioSnapshot.fromPcm16(generation, boundary, frame.capturedAtNanos, pcm)
        pcm.fill(0)
        val index = accepted
        val offer = shadow.offer(snapshot) { completion -> finished(index, completion) }
        evidence.configuration("smart_turn_last_offer", offer.name.lowercase())
        if (offer == SmartTurnOffer.ACCEPTED) {
            accepted++
            results[boundary] = index
            observedAt[index] = frame.capturedAtNanos
            evidence.configuration("smart_turn_${index}_worker_completion", "pending;late_completion_retained_in_diagnostics")
            evidence.metric("smart_turn_${index}_actual_worker_wall_ms", null)
            evidence.metric("smart_turn_${index}_post_priority_worker_wall_ms", null)
            evidence.metric("smart_turn_accepted_observations", accepted)
            evidence.metric("smart_turn_${index}_capture_boundary", boundary)
            evidence.metric("smart_turn_${index}_silence_ms", frame.silenceMs)
            evidence.metric("smart_turn_${index}_source_age_ms", (now - frame.capturedAtNanos) / 1e6)
        }
    }

    private fun drainResult() {
        val result = shadow.takeResult(generation) ?: return
        val index = results[result.captureSampleBoundary] ?: return
        evidence.metric("smart_turn_${index}_probability", result.probability)
        evidence.configuration("smart_turn_${index}_outcome", result.unknown?.name?.lowercase() ?: "observed_only")
        evidence.event("smart_turn_shadow observation=$index probability=${result.probability} unknown=${result.unknown?.name ?: "none"}")
    }

    @Synchronized private fun finished(index: Int, completion: SmartTurnWorkCompletion) {
        // Exact old-request metadata remains safe after generation change. No probability is published here.
        val prefix = "smart_turn_${index}_"
        evidence.metric(prefix + "initialization_ms", completion.timing.initializationNanos?.div(1e6))
        evidence.metric(prefix + "frontend_ms", completion.timing.frontendNanos?.div(1e6))
        evidence.metric(prefix + "inference_ms", completion.timing.inferenceNanos?.div(1e6))
        evidence.metric(prefix + "actual_worker_wall_ms", completion.timing.totalNanos / 1e6)
        evidence.metric(prefix + "cancelled", if (completion.cancelled) 1 else 0)
        val overlapMs = priorityAt?.let { priority ->
            (completion.finishedAtNanos - maxOf(priority, completion.startedAtNanos)).coerceAtLeast(0) / 1e6
        }
        evidence.metric(prefix + "post_priority_worker_wall_ms", overlapMs)
        evidence.configuration(prefix + "worker_completion", "measured;completion_record_retained_in_diagnostics")
        evidence.completion(index, completion, overlapMs)
    }

    @Synchronized override fun onPriority(reason: String) {
        if (closed || prioritized) return
        drainResult()
        prioritized = true
        priorityAt = clock()
        val busyAtPriority = shadow.isBusy()
        generation = generation.copy(revision = generation.revision + 1)
        shadow.updateGeneration(generation)
        ring.fill(0); size = 0
        evidence.configuration("smart_turn_revoked_for_priority", reason)
        evidence.metric("smart_turn_busy_at_priority", if (busyAtPriority) 1 else 0)
    }

    @Synchronized override fun onInvalidated(reason: String) {
        if (closed) return
        generation = generation.copy(revision = generation.revision + 1)
        shadow.updateGeneration(generation)
        ring.fill(0); size = 0; next = 0; possibleSpeech = true; hasBoundary = false
        evidence.configuration("smart_turn_capture_invalidation", reason)
    }

    @Synchronized override fun close() {
        if (closed) return
        drainResult()
        closed = true
        generation = generation.copy(revision = generation.revision + 1)
        shadow.updateGeneration(generation)
        ring.fill(0); size = 0
        evidence.metric("smart_turn_busy_at_capture_close", if (shadow.isBusy()) 1 else 0)
    }
}
