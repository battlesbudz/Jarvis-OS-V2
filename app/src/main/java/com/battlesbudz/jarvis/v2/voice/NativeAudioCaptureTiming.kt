package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.diagnostics.NativeAudioTimingMetrics
import com.google.ai.edge.litertlm.NativeAudioTimingReceipt
import com.google.ai.edge.litertlm.NativeAudioTimingPhase
import com.google.ai.edge.litertlm.NativeNanoInterval
import com.google.ai.edge.litertlm.NativeWorkBeforeKind

/** Ephemeral final-candidate clock data. Persist only [metrics]' content-free relative values. */
internal data class NativeAudioCaptureTiming(
    val receipt: NativeAudioTimingReceipt,
    val sealCalledAtNs: Long,
    val sealReturnedAtNs: Long,
    val checkedCloseCalledAtNs: Long? = null,
    val checkedCloseAtNs: Long? = null,
    val endpointDecisionAtNs: Long? = null,
    val retainedPreRollSampleCount: Int? = null,
) {
    fun metrics(playbackAtNs: Long? = null): NativeAudioTimingMetrics? = runCatching {
        require(receipt.phase == NativeAudioTimingPhase.SEALED)
        require(!receipt.mappingAvailable || receipt.nativeTimingValid && receipt.verifiedClockContract)
        val closedAt = requireNotNull(checkedCloseAtNs)
        require(Math.subtractExact(sealReturnedAtNs, sealCalledAtNs) >= 0)
        val closeCalledAt = requireNotNull(checkedCloseCalledAtNs)
        require(Math.subtractExact(closeCalledAt, sealReturnedAtNs) >= 0)
        val closeNs = Math.subtractExact(closedAt, closeCalledAt)
        require(closeNs >= 0)
        val admission = receipt.firstAccepted
        val playback = playbackAtNs?.let { NativeNanoInterval.point(it) }
        val firstAudioToSpeech = if (receipt.mappingAvailable) playback?.let { admission?.elapsedTo(it) } else null
        val firstStep = if (receipt.mappingAvailable) receipt.firstStep?.let { admission?.elapsedTo(it) } else null
        val endpoint = endpointDecisionAtNs?.let { NativeNanoInterval.point(it) }
        val endpointFromAdmission = if (receipt.mappingAvailable) endpoint?.let { admission?.elapsedTo(it) } else null
        val before = endpoint?.takeIf { receipt.mappingAvailable }?.let(receipt::workBefore)
        val measuredBefore = before?.takeIf { it.kind != NativeWorkBeforeKind.UNAVAILABLE }
        val usablePlayback = firstAudioToSpeech?.takeIf { it.lowerNanos >= 0 }
        NativeAudioTimingMetrics(
            retainedPcmSamples = receipt.acceptedPcmSamples,
            retainedPreRollSamples = retainedPreRollSampleCount,
            completedSteps = receipt.completedSteps,
            validatedRows = receipt.validatedAudioRows,
            clockMappingAvailable = receipt.mappingAvailable,
            alignmentUncertaintyNanos = receipt.alignmentUncertaintyNanos.takeIf { receipt.mappingAvailable },
            firstAudioToSpeechLowerMs = usablePlayback?.lowerNanos?.div(1_000_000.0),
            firstAudioToSpeechUpperMs = usablePlayback?.upperNanos?.div(1_000_000.0),
            firstStepFromAdmissionLowerMs = firstStep?.lowerNanos?.div(1_000_000.0),
            firstStepFromAdmissionUpperMs = firstStep?.upperNanos?.div(1_000_000.0),
            endpointFromAdmissionLowerMs = endpointFromAdmission?.lowerNanos?.div(1_000_000.0),
            endpointFromAdmissionUpperMs = endpointFromAdmission?.upperNanos?.div(1_000_000.0),
            stepsBeforeEndpointLowerBound = measuredBefore?.stepLowerBound,
            stepsBeforeEndpointExact = measuredBefore?.exactStepCount,
            rowsBeforeEndpointExact = measuredBefore?.exactRowCount,
            checkedCloseDurationMs = closeNs / 1_000_000.0,
        )
    }.getOrNull()
}
