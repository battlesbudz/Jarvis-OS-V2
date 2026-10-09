package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Captures one speech turn, with bounded idle pre-roll and no raw audio on disk. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioTurnCapture(
    private val input: AudioInput,
    private val scope: CoroutineScope,
    private val createDetector: () -> SpeechDetector,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val createTranscriber: (() -> StreamingTranscriber)? = null,
    private val onPartialTranscript: (String) -> Unit = {},
    private val onMetrics: (AsrCaptureMetrics, String) -> Unit = { _, _ -> },
    private val trailingSilenceMs: Long? = null,
    private val onRecognitionRecovery: (Boolean) -> Unit = {},
    private val allowAudioOnlyTurns: Boolean = false,
    private val guardFollowupSpeech: Boolean = false,
    private val initialConfirmedSpeech: () -> String = { "" },
    private val onSpeechResumed: () -> Unit = {},
    private val turnEnd: TurnEndDetector = AdaptiveTurnEnd(),
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxAudioDurationMs: Int = 25_000,
    private val rejectAtAudioLimit: Boolean = false,
    /** Display-only recognition cannot veto acoustically confirmed native audio. */
    private val captionOnly: Boolean = false,
    private val onAcousticDecision: (ByteArray, SpeechDecision, SpeechDecision, Double) -> Unit = { _, _, _, _ -> },
    private val retainedPcmObserver: RetainedPcmObserver? = null,
    private val nowNs: () -> Long = System::nanoTime,
    /** Ordinary native audio only; caller checks playback-tail risk using the actual onset. */
    private val canRetireIdleCaption: (Long?) -> Boolean = { false },
    private val nativePauseObserver: NativePauseObserver? = null,
    private val nativePauseTurnId: String = "",
    private val nativePauseGeneration: Long = 0,
    private val canUseNativePause: (Long?) -> Boolean = { false }
) {
    private val nativePause = nativePauseObserver?.let { NativePauseCapture(it, nativePauseTurnId, nativePauseGeneration) }
    /** Only published by stop(), after capture's collector and VAD producer have joined. */
    @Volatile var nativePauseCertificate: NativePauseCertificate? = null
        private set
    private val pcm = RollingAudioBuffer(maxDurationMs = maxAudioDurationMs.toLong())
    private var capturedPcmBytes = 0L
    val audioIsComplete: Boolean get() = capturedPcmBytes <= maxAudioDurationMs.toLong() * 32
    @Volatile var recognitionIssue: String? = null
        private set
    private val preRoll = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 1200)
    private val recoveryAudio = RollingAudioBuffer(AudioFormat(input.sampleRateHz), maxDurationMs = 25_000)
    private val lifecycle = Mutex()
    private var collectionJob: Job? = null
    private var detector: SpeechDetector? = null
    private var transcriber: StreamingTranscriber? = null
    @Volatile var finalTranscript: String = ""
        private set
    /** Observational only: a skipped caption is never reported as final ASR. */
    @Volatile var finalAsrStatus: String = "pending"
        private set
    @Volatile var captionFinalizationReason: String = "not_attempted"
        private set
    private var lastPartial = ""
    private var partialUpdates = 0
    private var firstSpeechAt: Long? = null
    private var firstPartialAfterSpeechMs: Long? = null
    private var captureReadyMs = 0L
    private val turnCompleted = CompletableDeferred<Boolean>()
    private var stopped = false
    private fun newTranscriber(): StreamingTranscriber? = createTranscriber?.let { SegmentedTranscriber(it, log) }
    private val quietEvidence = QuietSpeechEvidence()
    @Volatile var firstSpeechCaptureAtMs: Long? = null
        private set
    @Volatile var lastSpeechAtMs: Long? = null
        private set
    /** Final accepted endpoint proposal, before recognizer finalization. Never last speech. */
    @Volatile var endpointDecisionAtNs: Long? = null
        private set
    /** Actual retained onset buffer, including the confirming frame, in PCM samples. */
    @Volatile var retainedPreRollSampleCount: Int? = null
        private set
    @Volatile private var endRequested = false
    fun finishNow() { nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED); endRequested = true }
    fun yieldMicrophone() { nativePause?.invalidate(NativePauseInvalidation.CANCELLED); turnCompleted.completeExceptionally(MicrophoneBusyException()) }
    @Volatile var hasSpeech: Boolean = false
        private set

    suspend fun start(initialSilenceTimeoutMs: Long? = VoiceCallPolicy.CALL_INACTIVITY_MS) = lifecycle.withLock {
        check(!stopped && collectionJob == null) { "Audio capture is already started or stopped." }
        check(input.sampleRateHz == 16_000 && input.channelCount == 1) { "VAD requires 16 kHz mono audio." }
        // Start the hardware first; AndroidAudioInput buffers PCM even without a collector.
        // Model construction must not erase speech spoken during microphone preparation.
        val captureRequestedAt = nowMs()
        input.start()
        val activeDetector = createDetector()
        detector = activeDetector
        val loadStartedAt = nowMs()
        transcriber = newTranscriber()
        var modelLoadMs = nowMs() - loadStartedAt
        val startedAt = nowMs()
        val speechQueue = CaptureSpeechQueue(input, activeDetector, nowMs, log, dispatcher = captureDispatcher, onDecision = onAcousticDecision,
            onRawDecision = { nativePause?.observeRaw(it) })
        speechQueue.targetSilenceMs = trailingSilenceMs ?: transcriber?.noTextSilenceMs ?: 3000L
        var audioBytes = 0L
        var asrInputStartSample = 0L
        var lastSpeechSample = 0L
        val completedCues = NativeCaptionEndpointCueTracker()
        val completedCueEnd = AdaptiveTurnEnd()
        val nativeSpeechEvidence = CandidateSpeechEvidence()
        var lastNativeEligibilityReason = ""
        var nativeEligibleFrames = 0
        var nativeUncoveredEndpointFrames = 0
        var nativeProposalCount = 0
        var decodeMs = 0L
        var maxDecodeChunkMs = 0L
        var emptyCandidates = 0
        var deferredPartialChunks = 0
        var finalDecodeMs = 0L
        val recognitionBudget = CaptureRecognitionBudget()
        var maxRecognitionBacklogMs = 0L
        val acousticMetrics = CaptureAcousticAccumulator()
        val recognitionWork = AsrRecognitionWorkLedger()
        var lastSpeechAt = startedAt
        var lastLevelLogAt = startedAt
        var pendingEndpoint = false
        var finalNativeEndpointGuard: AdaptiveTurnEnd.Decision? = null
        var retiredCaptionAtPendingEndpoint = false
        var quietEvidenceUsed = false
        var unconfirmedOnsetAt: Long? = null
        val followupEvidence = FollowupSpeechEvidence()
        var initialEvidenceAvailable = true
        val pendingAudio = RollingAudioBuffer(maxDurationMs = 1200)
        log("capture_started vad=silero threshold=0.5 speechConfirmationMs=96 " +
            "speechGate=confirmed_acoustic_v4 noiseWindowMs=3000 noiseCalibrationMs=200 weakNoiseRatio=1.8 strongNoiseRatio=1.1 partialPolicy=work_paced_v1 " +
            "endpointing=${if (trailingSilenceMs == null) "adaptive" else "fixed"} " +
            "trailingSilenceMs=$trailingSilenceMs initialSilenceTimeoutMs=$initialSilenceTimeoutMs audioWindowMs=$maxAudioDurationMs maxTurnMs=${if (rejectAtAudioLimit) maxAudioDurationMs else 120000}")
        captureReadyMs = nowMs() - captureRequestedAt
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var acceptedTurn: Boolean? = null
                speechQueue.frames().transformWhile { emit(it); acceptedTurn == null && !turnCompleted.isCompleted }.collect { frame ->
                    if (turnCompleted.isCompleted) return@collect
                    speechQueue.consumed(frame)
                    val chunk = frame.pcm
                    audioBytes += chunk.size
                    recoveryAudio.append(chunk)
                    val signal = Pcm16Signal.measure(chunk)
                    val decision = frame.decision
                    acousticMetrics.record(signal, decision, frame.noiseFloorRms)
                    nativeSpeechEvidence.observe(chunk.size, decision)
                    val now = nowMs()
                    val audioAt = frame.capturedAtMs
                    // Give a possible onset one confirmation window before sealing.
                    // It cannot refresh lastSpeechAt or reopen ASR on its own.
                    if (!decision.isSpeech && decision.probability >= 0.5f) {
                        if (unconfirmedOnsetAt == null) unconfirmedOnsetAt = audioAt
                    } else unconfirmedOnsetAt = null
                    val awaitingConfirmation = unconfirmedOnsetAt?.let { audioAt - it < 96 } == true
                    var resumedAudio: ByteArray? = null
                    if (pendingEndpoint) pendingAudio.append(chunk)
                    if (pendingEndpoint && (decision.isSpeech || (nativePause != null &&
                        (frame.rawDecision.probability >= .15f || frame.rawDecision.rawCoverage == null)))) {
                        (transcriber as? SegmentedTranscriber)?.resumeAfterEndpoint()
                        pendingEndpoint = false
                        finalNativeEndpointGuard = null
                        retiredCaptionAtPendingEndpoint = false
                        resumedAudio = pendingAudio.snapshot(); pendingAudio.clear()
                        onSpeechResumed()
                        log("turn_endpoint_invalidated reason=resumed_speech")
                    }
                    var retainedAudio: ByteArray? = null
                    var retainedWindowRolled = false
                    synchronized(pcm) {
                        if (hasSpeech) {
                            pcm.append(chunk)
                            if (retainedPcmObserver != null || nativePause != null) retainedAudio = chunk.copyOf()
                            val wasComplete = audioIsComplete
                            capturedPcmBytes += chunk.size
                            if (wasComplete && !audioIsComplete) {
                                retainedWindowRolled = true
                                onSpeechResumed()
                                log("audio_window_rolled full_request_text_required=true speculation_invalidated=true")
                            }
                        } else {
                            preRoll.append(chunk)
                        }
                        if (decision.isSpeech) {
                            if (hasSpeech && audioAt - lastSpeechAt >= 180) onSpeechResumed()
                            if (!hasSpeech) {
                                firstSpeechAt = now
                                firstSpeechCaptureAtMs = audioAt
                                val acceptedPreRoll = preRoll.snapshot()
                                pcm.append(acceptedPreRoll)
                                retainedPreRollSampleCount = (pcm.sizeBytes() / 2).toInt()
                                if (retainedPcmObserver != null || nativePause != null) retainedAudio = pcm.snapshot()
                                capturedPcmBytes = pcm.sizeBytes()
                                preRoll.clear()
                                log("speech_started vad=silero elapsedMs=${now - startedAt}")
                            }
                            hasSpeech = true
                            lastSpeechAt = audioAt
                            lastSpeechAtMs = audioAt
                            lastSpeechSample = audioBytes / 2
                        }
                    }
                    // Observe the retained request, not raw/VAD frames. The copied
                    // bytes leave the PCM lock before any external queue admission.
                    if (retainedWindowRolled) {
                        nativePause?.invalidate(NativePauseInvalidation.WINDOW_ROLLED)
                        retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.WINDOW_ROLLED)
                    }
                    if (audioIsComplete) retainedAudio?.let {
                        nativePause?.retain(it)
                        if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onPcm(it)
                    }
                    // ASR receives every frame from microphone startup. VAD controls
                    // submission and endpointing, not whether initial words reach the recognizer.
                    val decodeStartedAt = nowMs()
                    if (!pendingEndpoint) transcriber?.observeSpeech(decision.isSpeech)
                    val backlogMs = speechQueue.bufferedAudioMs
                    maxRecognitionBacklogMs = maxOf(maxRecognitionBacklogMs, backlogMs)
                    val allowPartial = recognitionBudget.allows(decodeStartedAt, backlogMs) &&
                        !(hasSpeech && decision.probability < 0.15f)
                    if (!pendingEndpoint && !allowPartial) deferredPartialChunks++
                    val segmentsBefore = (transcriber as? SegmentedTranscriber)?.segments
                    val partial = if (pendingEndpoint) null else transcriber?.accept(resumedAudio ?: chunk, allowPartial)?.let {
                        // An unchanged cached hypothesis is not fresh evidence of speech
                        // in a queued or silent frame. Finalization still consumes all PCM.
                        val committed = (transcriber as? SegmentedTranscriber)?.segments != segmentsBefore
                        if (!allowPartial && !committed) null else if (TranscriptContent.isSoundOnly(it)) "" else TranscriptContent.speech(it)
                    }
                    currentCoroutineContext().ensureActive()
                    if (turnCompleted.isCompleted) return@collect
                    val chunkDecodeMs = nowMs() - decodeStartedAt
                    if (!pendingEndpoint) recognitionBudget.completed(decodeStartedAt, nowMs())
                    decodeMs += chunkDecodeMs
                    maxDecodeChunkMs = maxOf(maxDecodeChunkMs, chunkDecodeMs)
                    // Stable words corroborate weak whisper VAD; blank/noisy audio cannot
                    // qualify on amplitude alone. Strong VAD retains its existing fast path.
                    val corroborated = quietEvidence.accept(if (allowPartial) partial else null, decision.probability, audioAt, hasSpeech)
                    followupEvidence.observe(decision.speechSamples?.times(2) ?: chunk.size, decision.probability, partial?.takeIf { it.isNotBlank() } ?: initialConfirmedSpeech().takeIf { initialEvidenceAvailable && it.isNotBlank() }, corroborated, decision.isSpeech)
                    if (corroborated) {
                        quietEvidenceUsed = true
                        nativePause?.invalidate(NativePauseInvalidation.RESUMED_OR_UNCERTAIN_AUDIO)
                    }
                    if (hasSpeech && corroborated) {
                        if (audioAt - lastSpeechAt >= 180) onSpeechResumed()
                        lastSpeechAt = audioAt
                        lastSpeechAtMs = audioAt
                        lastSpeechSample = audioBytes / 2
                    }
                    if (!hasSpeech && corroborated) {
                        var acceptedPreRoll: ByteArray? = null
                        synchronized(pcm) {
                            firstSpeechAt = now
                                firstSpeechCaptureAtMs = audioAt
                            val onsetPcm = preRoll.snapshot()
                            pcm.append(onsetPcm)
                            retainedPreRollSampleCount = (pcm.sizeBytes() / 2).toInt()
                            if (retainedPcmObserver != null || nativePause != null) acceptedPreRoll = pcm.snapshot()
                            capturedPcmBytes = pcm.sizeBytes()
                            preRoll.clear()
                            hasSpeech = true
                            lastSpeechAt = audioAt
                            lastSpeechAtMs = audioAt
                            lastSpeechSample = audioBytes / 2
                        }
                        acceptedPreRoll?.let {
                            nativePause?.retain(it)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onPcm(it)
                        }
                        log("speech_started source=asr_and_vad probability=${decision.probability} preRollMs=1200")
                    }
                    val cue = if (captionOnly && nativePause != null) transcriber?.completedEndpointCue else null
                    if (hasSpeech && partial != null) publishPartial(partial)
                    if (captionOnly && nativePause != null && completedCues.update(cue,
                            lastSpeechSample - asrInputStartSample, now, completedCueEnd)) {
                        log("native_endpoint_cue source=completed_asr coveredSamples=${cue?.coveredAudioSamples} " +
                            "latestSpeechSample=${lastSpeechSample - asrInputStartSample} speechClockUnchanged=true")
                    }
                    val rawCoverage = frame.rawDecision.rawCoverage
                    val exactCollectorCoverage = rawCoverage != null && rawCoverage.receivedPcmBytes == audioBytes &&
                        rawCoverage.classifiedThroughSample * 2 == audioBytes
                    val unsegmented = (transcriber as? SegmentedTranscriber)?.segments?.let { it == 0 } != false
                    val nativeEligibility = NativePauseEndpointPolicy.eligibility(hasSpeech,
                        nativeSpeechEvidence.snapshot().strongMs, quietEvidenceUsed, audioAt - lastSpeechAt,
                        frame.rawDecision.rawCoverage, awaitingConfirmation, speechQueue.bufferedAudioMs, audioIsComplete)
                        .let { if (!captionOnly || nativePause == null || !unsegmented || !canUseNativePause(firstSpeechCaptureAtMs))
                            NativePauseEndpointPolicy.Eligibility(false, "route_or_playback_guard") else it }
                    val legacyEndpoint = trailingSilenceMs?.let { AdaptiveTurnEnd.Decision(it, "fixed") }
                        ?: turnEnd.decision(now).let {
                            if (it.cue == "no_transcript") it.copy(silenceMs = transcriber?.noTextSilenceMs ?: it.silenceMs) else it
                        }
                    val baseEndpoint = finalNativeEndpointGuard ?: if (trailingSilenceMs != null) legacyEndpoint
                        else if (captionOnly && nativePause != null && unsegmented) completedCues.decision(
                            legacyEndpoint, lastSpeechSample - asrInputStartSample,
                            completedCueEnd.decision(now).takeIf {
                                (exactCollectorCoverage && nativeEligibility.allowed) || it.silenceMs >= legacyEndpoint.silenceMs
                            }) else legacyEndpoint
                    val endpoint = if (trailingSilenceMs == null) NativePauseEndpointPolicy.decision(
                        baseEndpoint, nativeEligibility, frame.rawDecision.rawCoverage, audioBytes, legacyEndpoint) else baseEndpoint
                    if (nativeEligibility.allowed) nativeEligibleFrames++
                    if (nativeEligibility.allowed && !exactCollectorCoverage) nativeUncoveredEndpointFrames++
                    val nativeReason = if (nativeEligibility.allowed && !exactCollectorCoverage)
                        "raw_tail_unclassified" else nativeEligibility.reason
                    if (nativePause != null && nativeReason != lastNativeEligibilityReason) {
                        lastNativeEligibilityReason = nativeReason
                        log("native_pause_eligibility reason=$nativeReason baseCue=${baseEndpoint.cue} " +
                            "endpointCue=${endpoint.cue} rawCoveredSamples=${frame.rawDecision.rawCoverage?.classifiedThroughSample} " +
                            "collectedSamples=${audioBytes / 2}")
                    }
                    if (nativePause?.canPropose == true && nativeEligibility.allowed && !pendingEndpoint && NativePauseEndpointPolicy.permitsProposal(baseEndpoint)) {
                        val snapshot = synchronized(pcm) { pcm.snapshot() }
                        if (nativePause.propose(snapshot, audioBytes / 2, nowNs())) {
                            nativeProposalCount++
                            log("native_pause_frozen samples=${snapshot.size / 2} captureBoundary=${audioBytes / 2} policy=native_frozen_pause_raw_coverage_v1")
                        }
                    }
                    speechQueue.targetSilenceMs = endpoint.silenceMs
                    var reason = when {
                        endRequested -> "explicit_stop"
                        hasSpeech && audioAt - lastSpeechAt >= endpoint.silenceMs &&
                            !awaitingConfirmation && speechQueue.bufferedAudioMs == 0L -> "trailing_silence"
                        rejectAtAudioLimit && hasSpeech && capturedPcmBytes >= maxAudioDurationMs.toLong() * 32 -> "audio_input_limit"
                        hasSpeech && capturedPcmBytes >= 120L * 32_000 -> "utterance_capacity"
                        !hasSpeech && initialSilenceTimeoutMs != null && now - lastSpeechAt >= initialSilenceTimeoutMs -> "initial_silence"
                        else -> null
                    }
                    if (reason != null && !turnCompleted.isCompleted) {
                        if (reason != "trailing_silence") nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED)
                        // This proposal can still be invalidated by new speech or
                        // final recognition. Publish it only with the accepted candidate.
                        val proposedEndpointAtNs = nowNs()
                        val finalizeStartedAt = nowMs()
                        val acousticAccepted = !guardFollowupSpeech || !hasSpeech || followupEvidence.accepts()
                        if (guardFollowupSpeech && hasSpeech && !pendingEndpoint) {
                            log("followup_speech_evidence accepted=$acousticAccepted ${followupEvidence.diagnostic()}")
                        }
                        currentCoroutineContext().ensureActive()
                        if (turnCompleted.isCompleted) return@collect
                        if (!acousticAccepted) {
                            log("followup_candidate_rejected microphone=kept_open elapsedMs=${now - startedAt}")
                            onSpeechResumed()
                            hasSpeech = false
                            finalTranscript = ""
                            synchronized(pcm) { pcm.clear(); capturedPcmBytes = 0; preRoll.clear() }
                            nativePause?.invalidate(NativePauseInvalidation.CANDIDATE_DISCARDED)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onCandidateDiscarded()
                            retainedPreRollSampleCount = null
                            followupEvidence.reset(); initialEvidenceAvailable = false; recoveryAudio.clear()
                            pendingEndpoint = false; finalNativeEndpointGuard = null; retiredCaptionAtPendingEndpoint = false
                            pendingAudio.clear(); recognitionIssue = null
                            firstSpeechCaptureAtMs = null; firstSpeechAt = null; firstPartialAfterSpeechMs = null; lastPartial = ""
                            quietEvidence.reset(); quietEvidenceUsed = false; turnEnd.reset()
                            nativeSpeechEvidence.reset(); completedCues.reset(); completedCueEnd.reset(); asrInputStartSample = audioBytes / 2
                            transcriber?.let { previous ->
                                previous.close()
                                recognitionWork.retain(previous.recognitionWorkMetrics)
                            }
                            transcriber = null
                            if (initialSilenceTimeoutMs != null && now - startedAt >= initialSilenceTimeoutMs) {
                                turnCompleted.complete(false)
                                return@collect
                            }
                            transcriber = newTranscriber()
                            lastSpeechAt = startedAt
                            lastSpeechAtMs = null
                            return@collect
                        }
                        if (hasSpeech) {
                            val finishAt = nowMs()
                            if (retiredCaptionAtPendingEndpoint && reason != "trailing_silence") {
                                // Explicit stop/capacity are guarded endpoints even if an earlier
                                // silence proposal retired captions before hardware drain.
                                (transcriber as? SegmentedTranscriber)?.resumeAfterEndpoint()
                                retiredCaptionAtPendingEndpoint = false
                                pendingEndpoint = false
                                // Every pending frame was retained acoustically but skipped by
                                // ASR. Replay that exact tail once before guarded finalization.
                                transcriber?.observeSpeech(decision.isSpeech)
                                val guardedTail = pendingAudio.snapshot()
                                pendingAudio.clear()
                                if (guardedTail.isNotEmpty()) transcriber?.accept(guardedTail, allowPartial = false)
                            }
                            captionFinalizationReason = when {
                                retiredCaptionAtPendingEndpoint -> "clean_native_idle_pending_drain"
                                !captionOnly -> "authoritative_asr"
                                reason != "trailing_silence" -> "endpoint_$reason"
                                !audioIsComplete -> "incomplete_audio"
                                pendingEndpoint -> "pending_endpoint"
                                quietEvidenceUsed -> "quiet_asr_evidence"
                                followupEvidence.strongMs < 240 -> "short_strong_audio"
                                !canRetireIdleCaption(firstSpeechCaptureAtMs) -> "route_or_playback_guard"
                                transcriber?.retireIdleCaption() == true -> "clean_native_idle"
                                else -> "worker_busy_or_unsupported"
                            }
                            val idleCaptionRetired = retiredCaptionAtPendingEndpoint || captionFinalizationReason == "clean_native_idle"
                            retiredCaptionAtPendingEndpoint = idleCaptionRetired
                            // Provisional display words never become final request/echo/control
                            // evidence. Native PCM is already the request on this eligible path.
                            val rawFinal = if (idleCaptionRetired) "" else transcriber?.finish().orEmpty().trim()
                            finalAsrStatus = when {
                                idleCaptionRetired -> "skipped_idle_caption"
                                transcriber == null -> "unavailable"
                                else -> "finalized"
                            }
                            if (captionOnly) log("caption_finalization reason=$captionFinalizationReason " +
                                "newFinalDecodeSkipped=$idleCaptionRetired finalAsrStatus=$finalAsrStatus " +
                                "strongAudioMs=${followupEvidence.strongMs}")
                            finalDecodeMs += nowMs() - finishAt
                            currentCoroutineContext().ensureActive()
                            if (turnCompleted.isCompleted) return@collect
                            if (reason == "trailing_silence" && endpoint.silenceMs < legacyEndpoint.silenceMs &&
                                rawFinal.isNotBlank() && !TranscriptContent.isSoundOnly(rawFinal)) {
                                val finalCue = AdaptiveTurnEnd().also { it.update(TranscriptContent.speech(rawFinal), nowMs()) }.decision(nowMs())
                                if (finalCue.cue in setOf("unfinished", "uncertain", "explicit_hesitation") &&
                                    audioAt - lastSpeechAt < finalCue.silenceMs) {
                                    nativePause?.invalidate(NativePauseInvalidation.ENDPOINT_REJECTED)
                                    turnEnd.update(TranscriptContent.speech(rawFinal), nowMs())
                                    finalNativeEndpointGuard = finalCue
                                    completedCues.reset(); completedCueEnd.reset()
                                    pendingEndpoint = true; pendingAudio.clear()
                                    log("native_endpoint_deferred reason=final_caption_${finalCue.cue} targetSilenceMs=${finalCue.silenceMs}")
                                    return@collect
                                }
                            }
                            recognitionIssue = if (reason == "audio_input_limit") "gemma_audio_request_exceeds_limit"
                                else (transcriber as? SegmentedTranscriber)?.issue
                                    ?: if (reason == "utterance_capacity") "utterance_capacity" else null
                            if (reason == "audio_input_limit" || reason == "utterance_capacity") {
                                nativePause?.invalidate(NativePauseInvalidation.AUDIO_LIMIT)
                                retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.AUDIO_LIMIT)
                            }
                            if (!audioIsComplete && rawFinal.isBlank()) recognitionIssue = "missing_long_transcript"
                            // ASR finalization can take time. Consume new possible speech
                            // or unclassified hardware audio before accepting an old endpoint.
                            // Already classified silence alone cannot invalidate it. Retain the
                            // final words as a segment and continue on the same hardware reader.
                            if (reason == "trailing_silence" && speechQueue.bufferedAudioMs > 0 &&
                                (if (nativePause != null) speechQueue.requiresNativeEndpointDrain(audioBytes) else speechQueue.requiresEndpointDrain(audioAt)) &&
                                transcriber is SegmentedTranscriber) {
                                if (!pendingEndpoint) {
                                    pendingAudio.clear()
                                    log("turn_endpoint_deferred reason=audio_arrived_during_finalization")
                                }
                                pendingEndpoint = true
                                return@collect
                            }
                            var resolvedFinal = rawFinal
                            if (audioIsComplete && recognitionIssue in setOf("unrecognized_segment", "segment_boundary_uncertain")) {
                                val completePcm = synchronized(pcm) { pcm.snapshot() }
                                log("asr_recovery_started reason=$recognitionIssue source=complete_turn audioMs=${completePcm.size / 32}")
                                val recoveryStarted = nowMs()
                                val recovered = transcriber?.recover(completePcm).orEmpty().trim()
                                finalDecodeMs += nowMs() - recoveryStarted
                                currentCoroutineContext().ensureActive()
                                if (recovered.isNotBlank() && !TranscriptContent.isSoundOnly(recovered)) {
                                    resolvedFinal = recovered
                                    recognitionIssue = null
                                    log("asr_recovery_finished source=complete_turn accepted=true chars=${recovered.length}")
                                } else log("asr_recovery_finished source=complete_turn accepted=false")
                            }
                            val nonverbal = TranscriptContent.isSoundOnly(resolvedFinal)
                            finalTranscript = if (nonverbal) "" else TranscriptContent.speech(resolvedFinal)
                            if (nonverbal) log(if (captionOnly) "nonverbal_caption ignored=true original_audio_retained=true" else "nonverbal_candidate ignored=true destination=none microphone=kept_open")
                            if (transcriber != null && finalTranscript.isBlank() && !allowAudioOnlyTurns && !nonverbal && recognitionIssue == null) {
                                val candidate = recoveryAudio.snapshot()
                                val recoveryAt = nowMs()
                                log("asr_recovery_started candidateAudioMs=${candidate.size / 32} reason=empty_stream source=full_capture_window nativeVad=bypassed")
                                onRecognitionRecovery(true)
                                try {
                                    finalTranscript = TranscriptContent.speech(transcriber?.recover(candidate).orEmpty())
                                    log("asr_recovery_finished chars=${finalTranscript.length} elapsedMs=${nowMs() - recoveryAt}")
                                } finally {
                                    onRecognitionRecovery(false)
                                }
                            }
                            if (!captionOnly && transcriber != null && finalTranscript.isBlank() && (!allowAudioOnlyTurns || nonverbal) && recognitionIssue == null) {
                                emptyCandidates++
                                hasSpeech = false
                                // A new word may be starting in the final, not-yet-confirmed
                                // VAD frame. Replay the tail into the replacement recognizer.
                                val tail = synchronized(pcm) {
                                    val recorded = pcm.snapshot()
                                    val retained = recorded.copyOfRange((recorded.size - 38_400).coerceAtLeast(0), recorded.size)
                                    pcm.clear(); capturedPcmBytes = 0
                                    preRoll.clear()
                                    preRoll.append(retained)
                                    retained
                                }
                                nativePause?.invalidate(NativePauseInvalidation.CANDIDATE_DISCARDED)
                            if (nativePause?.encoderFrozen != true) retainedPcmObserver?.onCandidateDiscarded()
                                retainedPreRollSampleCount = null
                                firstSpeechCaptureAtMs = null; firstSpeechAt = null
                                firstPartialAfterSpeechMs = null
                                lastPartial = ""
                                pendingEndpoint = false
                                finalNativeEndpointGuard = null
                                retiredCaptionAtPendingEndpoint = false
                                turnEnd.reset()
                                followupEvidence.reset()
                                initialEvidenceAvailable = false
                                quietEvidence.reset()
                                quietEvidenceUsed = false
                                nativeSpeechEvidence.reset(); completedCues.reset(); completedCueEnd.reset(); asrInputStartSample = audioBytes / 2 - tail.size / 2
                                onSpeechResumed()
                                log("empty_speech_candidate ignored=true count=$emptyCandidates microphone=kept_open inactivitySince=last_detected_speech")
                                if (initialSilenceTimeoutMs == null || nowMs() - (if (nonverbal) startedAt else lastSpeechAt) < initialSilenceTimeoutMs) {
                                    decodeMs += nowMs() - finalizeStartedAt
                                    // Finish seals an ASR stream, so replace only that stream/engine.
                                    // The microphone keeps buffering opening words during model reload.
                                    val previous = transcriber
                                    transcriber = null
                                    previous?.close()
                                    if (previous != null) recognitionWork.retain(previous.recognitionWorkMetrics)
                                    val reloadAt = nowMs()
                                    transcriber = newTranscriber()
                                    recoveryAudio.clear()
                                    recoveryAudio.append(tail)
                                    if (tail.isNotEmpty()) transcriber?.accept(tail)
                                    modelLoadMs += nowMs() - reloadAt
                                    return@collect
                                }
                                reason = "initial_silence"
                            }
                            if (hasSpeech && finalTranscript.isBlank() && allowAudioOnlyTurns && recognitionIssue == null) {
                                log("audio_only_turn speechDetected=true destination=gemma")
                            }
                            // The owner seals against finalTranscript. Sending it as a new
                            // partial would cancel a matching draft immediately before seal.
                            log("asr_final chars=${finalTranscript.length} segments=${(transcriber as? SegmentedTranscriber)?.segments} " +
                                "audioComplete=$audioIsComplete issue=$recognitionIssue")
                        }
                        onMetrics(AsrCaptureMetrics(modelLoadMs, captureReadyMs, audioBytes / 32,
                            decodeMs, maxDecodeChunkMs, firstPartialAfterSpeechMs, partialUpdates,
                            nowMs() - finalizeStartedAt, reason, emptyCandidates,
                            lastSpeechAtMs?.let { (finalizeStartedAt - it).coerceAtLeast(0) },
                            endpoint.silenceMs, endpoint.cue, acousticMetrics.snapshot(),
                            maxRecognitionBacklogMs, recognitionBudget.largestWorkMs, finalDecodeMs,
                            recognitionWork.withActive(transcriber?.recognitionWorkMetrics, transcriber != null)), finalTranscript)
                        log("capture_endpoint_timing reason=$reason " +
                            "speechEndToFinalMs=${lastSpeechAtMs?.let { (nowMs() - it).coerceAtLeast(0) }} " +
                            "silenceDetectedToFinalMs=${speechQueue.latestSilenceDetectedAtMs?.takeIf { it >= lastSpeechAt }?.let { (nowMs() - it).coerceAtLeast(0) }} " +
                            "finalDecodeMs=$finalDecodeMs deferredPartialChunks=$deferredPartialChunks " +
                            "maxRecognitionWorkMs=${recognitionBudget.largestWorkMs} maxRecognitionBacklogMs=$maxRecognitionBacklogMs " +
                            "recognitionBacklogMs=${speechQueue.bufferedAudioMs}")
                        if (nativePause != null) log("native_pause_capture_summary eligibleFrames=$nativeEligibleFrames " +
                            "uncoveredEndpointFrames=$nativeUncoveredEndpointFrames proposals=$nativeProposalCount " +
                            "finalCue=${endpoint.cue} targetSilenceMs=${endpoint.silenceMs}")
                        endpointDecisionAtNs = proposedEndpointAtNs.takeIf { hasSpeech }
                        acceptedTurn = hasSpeech
                        log("turn_endpoint reason=$reason elapsedMs=${now - startedAt} " +
                            "silenceMs=${audioAt - lastSpeechAt} endpointCue=${endpoint.cue} " +
                            "targetSilenceMs=${endpoint.silenceMs} speechDetected=$hasSpeech")
                    } else if (now - lastLevelLogAt >= 1_000L) {
                        lastLevelLogAt = now
                        log("capture_level vad=silero rms=${signal.rms.toInt()} peak=${signal.peak} " +
                            "probability=${decision.probability} speech=${decision.isSpeech} speechDetected=$hasSpeech " +
                            "silenceMs=${audioAt - lastSpeechAt} processingLagMs=${(nowMs() - audioAt).coerceAtLeast(0)} " +
                            "recognitionBacklogMs=${speechQueue.bufferedAudioMs}")
                    }
                }
                // flowOn's producer is cancelled and joined before the owner can
                // borrow the next reader. Prefetched but unacknowledged PCM stays
                // in the call session's history for that reader.
                if (acceptedTurn != null) turnCompleted.complete(acceptedTurn!!)
                else if (!turnCompleted.isCompleted) {
                    nativePause?.invalidate(NativePauseInvalidation.CAPTURE_FAILED)
                    retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CAPTURE_FAILED)
                    turnCompleted.completeExceptionally(IllegalStateException("Microphone stream ended before the turn completed."))
                }
            } catch (cancelled: CancellationException) {
                runCatching { nativePause?.invalidate(NativePauseInvalidation.CANCELLED) }
                runCatching { retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CANCELLED) }
                turnCompleted.cancel()
                throw cancelled
            } catch (error: Throwable) {
                // Deliver model/stream failures to the owner, not the Activity's uncaught handler.
                runCatching { nativePause?.invalidate(NativePauseInvalidation.CAPTURE_FAILED) }
                runCatching { retainedPcmObserver?.onCaptureInvalidated(RetainedPcmObserver.Invalidation.CAPTURE_FAILED) }
                turnCompleted.completeExceptionally(error)
            }
        }
        log("capture_ready pcmStartupMs=300 totalReadyMs=$captureReadyMs asrLoadMs=$modelLoadMs")
    }

    suspend fun awaitTurnCompletion(): Boolean = turnCompleted.await()

    suspend fun stop(): ByteArray = withContext(NonCancellable) {
        lifecycle.withLock {
            if (!stopped) {
                stopped = true
                val backlogBeforeStop = input.bufferedAudioMs
                try {
                    input.stop()
                } finally {
                    collectionJob?.cancel()
                    collectionJob?.join()
                    collectionJob = null
                    nativePauseCertificate = nativePause?.certificateAfterJoin(
                        synchronized(pcm) { pcm.snapshot() }, endpointDecisionAtNs,
                        maxOf(backlogBeforeStop, input.bufferedAudioMs,
                            if (input.stoppedUnconsumedPcmBytes == 0L) 0L else 1L))
                    turnCompleted.cancel()
                    try { transcriber?.close() } finally { detector?.close() }
                    transcriber = null
                    detector = null
                    recoveryAudio.clear()
                    log("capture_stopped speechDetected=$hasSpeech")
                }
            }
            synchronized(pcm) {
                WavEncoder.pcm16Mono(if (hasSpeech) pcm.snapshot() else preRoll.snapshot(), input.sampleRateHz)
            }
        }
    }

    private fun publishPartial(text: String) {
        val partial = text.trim()
        turnEnd.update(partial, nowMs())
        if (partial.isNotBlank() && partial != lastPartial) {
            lastPartial = partial
            partialUpdates++
            if (firstPartialAfterSpeechMs == null) {
                firstPartialAfterSpeechMs = firstSpeechAt?.let { (nowMs() - it).coerceAtLeast(0) }
            }
            log("asr_partial chars=${partial.length} truncated=${partial.length > 900} text=${partial.take(900)}")
            onPartialTranscript(partial)
        }
    }

    private companion object {
        const val MAX_TURN_BYTES = 25 * 16_000 * 2
    }
}
