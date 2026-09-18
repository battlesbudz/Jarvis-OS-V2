package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/** Local Jarvis voice output: Gemma text -> selected Sherpa model PCM -> Android audio route. */
class PiperVoiceOutput internal constructor(
    private val modelDirectory: String,
    private val engine: TtsEngine = TtsEngine.PIPER_NORTHERN,
    private val modelSession: VoiceModelSession? = null,
    private val deliveryLedger: SpeechDeliveryLedger? = null,
    private val onPlaybackEnded: () -> Unit = {},
    private val acknowledgeDelays: Boolean = false,
    private val playbackVolume: () -> String = { "unavailable" },
    private val audioTrace: SpeechAudioTrace? = null,
    private val onReady: () -> Unit = {},
    private val onPlayback: (VoicePlaybackFrame) -> Unit = {},
    private val onMetrics: (TtsSessionMetrics) -> Unit = {},
    private val speakerId: Int = engine.speaker,
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    private val numThreads = 4
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val sentenceRefilling = java.util.concurrent.atomic.AtomicBoolean(false)
    private val gapCuePlaying = AtomicBoolean(false)
    private val playbackLock = Any()
    @Volatile private var interrupted = false
    private val deliveryFailed = AtomicBoolean(false)
    private val playbackClock = PlaybackClock()
    private val spokenReference = StringBuilder()
    @Volatile private var playbackSpeakerReference: PlaybackSpeakerReference? = null
    private val speakerTimeline = PlaybackSpeakerTimeline()
    fun speakerReference(captureEndMs: Long, audioMs: Int): PlaybackSpeakerReference? =
        speakerTimeline.reference(captureEndMs, audioMs) ?: playbackSpeakerReference
    fun recentSpokenText(): String = synchronized(spokenReference) { spokenReference.toString() }
    private fun rememberPlayback(text: String) = synchronized(spokenReference) {
        if (text.isNotBlank()) spokenReference.append(" ").append(text)
        if (spokenReference.length > 1600) spokenReference.delete(0, spokenReference.length - 1600)
    }
    private suspend fun playCachedCue(audio: SpeechAudio) {
        synchronized(playbackLock) { gapCuePlaying.set(true); applyPause() }
        try {
            VoiceCues.playAcknowledgement(audio, { stopped }, { interrupted }, log, playbackVolume(),
                onStarted = {
                    playbackSpeakerReference = PlaybackSpeakerReference.fromPcm(audio.pcm, audio.sampleRate)
                    rememberPlayback(audio.text)
                }, speed = 1f)
        } finally {
            synchronized(playbackLock) { gapCuePlaying.set(false); applyPause() }
            lastAudibleAt = System.nanoTime() / 1_000_000
        }
    }
    @Volatile private var writtenFrames = 0L
    @Volatile private var lastAudibleAt = Long.MIN_VALUE / 2
    private val stoppedPlaybackHead = AtomicLong()
    private val acknowledgement = DelayedAcknowledgement(log)
    private val neutralFiller = FillerPhrases.INITIAL
    private val fillerDiskCache = FillerAudioCache(java.io.File(modelDirectory, "filler-cache-v3"))
    private val acknowledgementRequests = Channel<String>(Channel.CONFLATED)
    private fun fillerCacheKey(text: String) = "opening-v5:${engine.version}:$modelDirectory:$speakerId:$text:natural-pauses-v1"
    internal fun updateWaitStage(stage: DelayedAcknowledgement.Stage) { acknowledgement.updateStage(stage) }
    fun acknowledgeConfirmedTurn() {
        if (!acknowledgeDelays) return
        acknowledgement.request(neutralFiller)
    }
    private companion object {
        val acknowledgementCache = java.util.concurrent.ConcurrentHashMap<String, SpeechAudio>()
    }

    val isPlayingAudio: Boolean get() = synchronized(playbackLock) {
        val now = System.nanoTime() / 1_000_000
        val audible = !stopped && !interrupted && audioTrack?.let {
            it.playState == AudioTrack.PLAYSTATE_PLAYING && unsignedHead(it) < writtenFrames
        } == true
        if (audible) lastAudibleAt = now
        gapCuePlaying.get() || audible || now - lastAudibleAt < 350 // Speaker/reverberation tail after drain.
    }
    fun queuedPlaybackMs(): Long? = synchronized(playbackLock) {
        audioTrack?.let { track ->
            (writtenFrames - unsignedHead(track)).coerceAtLeast(0) * 1000 / track.sampleRate
        }
    }
    fun hasInterruptionBudget(): Boolean = DuplexPlaybackBudget.allows(
        queuedPlaybackMs(), continuing = false, unavailable = stopped || interrupted || gapCuePlaying.get() || sentenceRefilling.get())
    fun canContinueInterruption(): Boolean = DuplexPlaybackBudget.allows(
        queuedPlaybackMs(), continuing = true, unavailable = stopped || interrupted || gapCuePlaying.get() || sentenceRefilling.get())
    private fun applyPause() {
        val paused = interrupted || gapCuePlaying.get() || sentenceRefilling.get()
        playbackClock.setPaused(paused)
        audioTrack?.let { if (paused) it.pause() else if (!stopped) it.play() }
    }

    fun setInterrupted(value: Boolean) = synchronized(playbackLock) {
        if (interrupted == value) return@synchronized
        interrupted = value
        applyPause()
        log("audio_interruption paused=$value playbackHead=${audioTrack?.let(::unsignedHead) ?: 0}")
    }
    private suspend fun awaitPlaybackPermission() {
        while (interrupted && !stopped) delay(25)
        currentCoroutineContext().ensureActive()
    }

    private class FillerSuperseded : RuntimeException()
    private data class SynthesizedPhrase(
        val index: Int,
        val text: String,
        val sampleRate: Int,
        val pcm: ShortArray,
        val startupWaitMs: Long,
        val captionGroup: Int? = null,
        val sentenceEnd: Boolean = false
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        stoppedPlaybackHead.set(0)
        val speechScope = this
        if (acknowledgeDelays) {
            // Cached PCM needs no native model reload before it can be played.
            withContext(Dispatchers.IO) {
                for (text in (listOf(neutralFiller, FillerPhrases.RECOVERY))) {
                    val key = fillerCacheKey(text)
                    (acknowledgementCache[key] ?: fillerDiskCache.read(key, text))?.let {
                        acknowledgementCache[key] = it
                        acknowledgement.prepare(it)
                        log("acknowledgement_cache_hit beforeModelLoad=true text=$text source=generated_cache")
                    }
                }
            }
        }
        if (acknowledgeDelays) acknowledgement.start(this, requestPreparation = { acknowledgementRequests.trySend(it) }) { audio ->
            playCachedCue(audio)
        }
        log("tts_session_started engine=${engine.id} modelDir=$modelDirectory speaker=$speakerId threads=$numThreads workers=1")
        var framesWritten = 0
        var outputSampleRate = 0
        var totalSynthesisMs = 0L
        var totalAudioMs = 0L
        var totalQueueWaitMs = 0L
        var phraseCount = 0
        var loadMs = 0L
        var firstPcmMs: Long? = null
        var firstTextToPcmMs: Long? = null
        var firstTextToPlaybackMs: Long? = null
        val firstTextAt = AtomicLong()
        val firstAudibleFrame = AtomicLong(-1)
        var inputChars = 0
        val textHash = java.security.MessageDigest.getInstance("SHA-256")
        val captions = SpokenCaptionTimeline()
        var playbackMonitor: Job? = null
        var playbackConfirmed = false
        var completed = false
        var failureMessage: String? = null
        var finalUnderruns = 0
        val streamingUnderruns = AtomicLong(0)
        val playbackStarvationMs = AtomicLong(0)
        val draining = AtomicBoolean(false)
        val playbackSpeed = 1f
        var estimatedGapMs = 0L
        var sourcePcmSummary: String? = null
        val pcmDelivery = "piper_whole_passages_max640_v1"
        // One owner creates, invokes and releases the native engine. Playback never owns it.
        // Bounded PCM backpressure prevents long answers from accumulating unlimited audio.
        val audio = NativeAudioQueue<SynthesizedPhrase>(if (acknowledgeDelays) 8 else 2) {
            it.pcm.size * 1000L / it.sampleRate
        }
        val piperOpening = PiperTextStream.TARGET_CHARS
        val piperText = PiperTextStream()
        log("piper_text_policy version=natural-v1 targetChars=320 maxChars=640 nativeMaxNumSentences=0 speed=1.0 threads=4")
        val startupReady = CompletableDeferred<Unit>()
        val tokens = Channel<String>(64)
        val collectTokens = launch {
            try {
                chunks.collect { token ->
                    if (token.isNotBlank()) firstTextAt.compareAndSet(0, System.nanoTime())
                    tokens.send(token)
                }
                tokens.close()
            } catch (error: Throwable) { tokens.close(error); throw error }
        }
        val nativeDispatcher = modelSession?.ttsDispatcher ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "jarvis-tts").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val producer = launch(nativeDispatcher) {
            var engine: OfflineTts? = null
            var engineLease: CallModelSlot<OfflineTts>.Lease? = null
            var failure: Throwable? = null
            var index = 0
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val modelKey = "${this@PiperVoiceOutput.engine.id}:$modelDirectory:$numThreads:natural-v1"
                engineLease = modelSession?.tts?.acquire(modelKey) {
                    OfflineTts(config = sherpaTtsConfig(this@PiperVoiceOutput.engine, modelDirectory, numThreads, piperWholePassage = true))
                }
                val tts = engineLease?.value ?: OfflineTts(config =
                    sherpaTtsConfig(this@PiperVoiceOutput.engine, modelDirectory, numThreads, piperWholePassage = true))
                engine = tts
                loadMs = elapsedMs(loadStart)
                log("tts_engine_preload_finished loadMs=$loadMs reused=${engineLease?.reused == true}")
                val generation = GenerationConfig(silenceScale = 1f, sid = speakerId)
                onReady()
                fun synthesize(text: String, optionalFiller: Boolean = false): SpeechAudio {
                    owner.ensureActive()
                    val started = System.nanoTime()
                    log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
                    val generated = if (optionalFiller) {
                        // Stable Java callback; no PCM is played or cached until this preparation completes.
                        val callback = SherpaPcmCallback {
                            if (stopped || !owner.isActive || firstTextAt.get() != 0L) 0 else 1
                        }
                        val result = tts.generateWithConfigAndCallback(text, generation, callback)
                        callback.failure?.let { throw it }
                        owner.ensureActive()
                        if (stopped || firstTextAt.get() != 0L) throw FillerSuperseded()
                        result
                    } else tts.generateWithConfig(text, generation)
                    owner.ensureActive()
                    val rate = generated.sampleRate
                    check(rate > 0) { "Voice model returned an invalid sample rate." }
                    check(generated.samples.all { it.isFinite() }) { "Voice model returned non-finite PCM." }
                    val pcm = ShortArray(generated.samples.size) { i ->
                        (generated.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    val peak = generated.samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                    val rms = kotlin.math.sqrt(generated.samples.sumOf { it.toDouble() * it } /
                        generated.samples.size.coerceAtLeast(1))
                    val nonFinite = generated.samples.count { !it.isFinite() }
                    val clipped = generated.samples.count { kotlin.math.abs(it) >= 1f }
                    val leading = pcm.indexOfFirst { kotlin.math.abs(it.toInt()) >= 64 }.let { if (it < 0) pcm.size else it }
                    val trailing = pcm.indexOfLast { kotlin.math.abs(it.toInt()) >= 64 }.let { pcm.size - it - 1 }
                    log("tts_pcm_level rms=$rms peak=$peak frames=${pcm.size} nonFinite=$nonFinite clipped=$clipped " +
                        "leadingSilenceMs=${leading * 1000L / rate} trailingSilenceMs=${trailing * 1000L / rate}")
                    return SpeechAudio(text, rate, pcm, elapsedMs(started))
                }
                fun prepareAcknowledgement(text: String) {
                    try {
                        val key = fillerCacheKey(text)
                        val cached = acknowledgementCache[key] ?: run {
                            log("acknowledgement_cache_preparing text=$text")
                            FillerPcm.prepare(synthesize(text, optionalFiller = true))
                        }.also {
                            check(it.sampleRate > 0 && it.pcm.size in 1..it.sampleRate * 4) {
                                "Generated filler exceeded its four-second duration budget."
                            }
                            if (acknowledgementCache.size >= 12) acknowledgementCache.clear()
                            acknowledgementCache[key] = it
                            log("acknowledgement_cache_ready text=$text synthesisMs=${it.synthesisMs}")
                        }
                        acknowledgement.prepare(cached)
                        runCatching { fillerDiskCache.write(key, cached) }
                            .onFailure { log("acknowledgement_cache_persist_failed reason=${it.message}") }
                    } catch (_: FillerSuperseded) {
                        log("acknowledgement_preparation_yielded reason=answer_text_ready partial_not_cached=true")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        acknowledgement.preparationFailed(text)
                        log("acknowledgement_cache_unavailable reason=${error.message}")
                    }
                }
                // Optional filler synthesis is queued only during a gap, after cached audio is loaded.
                fun generate(text: String) {
                    owner.ensureActive()
                    if (stopped) return
                    run {
                        check(text.length <= PiperTextStream.MAX_CHARS)
                        log("piper_passage_submit index=$index chars=${text.length} nativeMaxNumSentences=0")
                        if (index == 0) log("piper_opening_wait_ms=${firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)} chars=${text.length}")
                    }
                    val result = synthesize(text)
                    if (stopped) return
                    val phraseIndex = index++
                    if (phraseIndex == 0) {
                        firstPcmMs = result.synthesisMs
                        firstTextToPcmMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                        log("tts_opening_ready prepared=false firstTextToPcmMs=$firstTextToPcmMs openingChars=$piperOpening")
                    }
                    val rate = result.sampleRate
                    val frames = result.pcm.size.toLong()
                    val waitStart = System.nanoTime()
                    deliveryLedger?.append(phraseIndex, text, result.pcm.size, rate)
                    deliveryLedger?.seal(phraseIndex)
                    audio.sendFromNative(SynthesizedPhrase(phraseIndex, text, rate, result.pcm,
                        PlaybackBufferPolicy.startupWaitMs(result.synthesisMs, frames * 1000 / rate)))
                    if (phraseIndex == 1) startupReady.complete(Unit)
                    val queueWaitMs = elapsedMs(waitStart)
                    val synthesisMs = result.synthesisMs
                    val audioMs = frames * 1000 / rate
                    val rtf = if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0
                    totalSynthesisMs += synthesisMs
                    totalAudioMs += audioMs
                    totalQueueWaitMs += queueWaitMs
                    phraseCount++
                    log("tts_generation_finished index=$phraseIndex synthesisMs=$synthesisMs queueWaitMs=$queueWaitMs " +
                        "audioDurationMs=$audioMs realtimeFactor=$rtf")
                }
                fun nextPhrase(final: Boolean = false): String? = piperText.take(final)
                var ended = false
                while (!ended && !stopped) {
                    owner.ensureActive()
                    select<Unit> {
                        // Prefer confirmed speech if both queues are ready.
                        tokens.onReceiveCatching { received ->
                            received.exceptionOrNull()?.let { throw it }
                            val token = received.getOrNull()
                            if (token == null) ended = true
                            else {
                                inputChars += token.length
                                textHash.update(token.toByteArray(Charsets.UTF_8))
                                run {
                                    piperText.append(token)
                                    while (true) {
                                        val queued = tokens.tryReceive()
                                        queued.exceptionOrNull()?.let { throw it }
                                        val more = queued.getOrNull() ?: break
                                        inputChars += more.length
                                        textHash.update(more.toByteArray(Charsets.UTF_8))
                                        piperText.append(more)
                                    }
                                }
                                while (true) generate(nextPhrase() ?: break)
                            }
                        }
                        piperText.openingWaitMs()?.let { remaining ->
                            onTimeout(remaining) {
                                while (true) generate(nextPhrase() ?: break)
                            }
                        }
                        acknowledgementRequests.onReceive { text ->
                            // Native owns one stream, not a session map: never replace an active answer with filler.
                            if (index == 0 && acknowledgeDelays && firstTextAt.get() == 0L) prepareAcknowledgement(text)
                        }

                    }
                }
                while (true) generate(nextPhrase(final = true) ?: break)
            } catch (error: Throwable) {
                failureMessage = error.message ?: error.javaClass.simpleName
                if (error !is CancellationException) deliveryFailed.set(true)
                failure = error
                throw error
            } finally {
                // Release only after generate has returned, including when cancellation was requested.
                startupReady.complete(Unit)
                // No more PCM can arrive. Publish completion before potentially slow native
                // cleanup, so cleanup is never mistaken for a gap needing another cue.
                audio.close(failure)
                if (engineLease != null) engineLease?.finish(healthy = failure == null && !stopped)
                else engine?.release()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                var first = true
                var lastWriteAt = 0L
                var lastQueuedMs = 0L
                var atSentenceBoundary = false
                var interveningCueMs = 0L
                var refillStartedAt = 0L
                val gapWaiter = SentenceGapWaiter()
                val gapAudio = acknowledgementCache[fillerCacheKey(FillerPhrases.RECOVERY)]
                while (true) {
                    val received = if (atSentenceBoundary && acknowledgeDelays) {
                        gapWaiter.receive(audio.chunks, remainingMs = {
                            audioTrack?.let { track ->
                                val frames = (writtenFrames - unsignedHead(track)).coerceAtLeast(0)
                                if (frames == 0L) 0L else maxOf(1L, (frames * 1000.0 / track.sampleRate / playbackSpeed).toLong())
                            } ?: 0L
                        }, bufferedMs = { audio.bufferedMs }, allowed = { !stopped && !interrupted },
                            productionFinished = { audio.productionFinished }, onRefill = { active ->
                                if (active) refillStartedAt = System.nanoTime()
                                else interveningCueMs += elapsedMs(refillStartedAt)
                                synchronized(playbackLock) { sentenceRefilling.set(active); applyPause() }
                                log("sentence_rebuffer active=$active bufferedAnswerMs=${audio.bufferedMs}")
                            }) {
                            if (gapAudio == null) return@receive
                            log("sentence_gap_filler text=${gapAudio.text} boundary=completed_sentence reason=prolonged_stall maxPerAnswer=1 excludes=answer_pcm")
                            try { playCachedCue(gapAudio) }
                            finally {
                                log("sentence_gap_filler_finished bufferedAnswerMs=${audio.bufferedMs}")
                            }
                        }
                    } else audio.chunks.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val phrase = received.getOrNull() ?: break
                    audio.consumed(phrase)
                    if (phrase.sentenceEnd) { atSentenceBoundary = true; continue }
                    atSentenceBoundary = false
                    ensureActive()
                    if (stopped || phrase.pcm.isEmpty()) continue
                    outputSampleRate = phrase.sampleRate
                    if (first) {
                        // Small startup headroom; never hold a short, completed answer for this delay.
                        val start = System.nanoTime()
                        acknowledgement.answerReady()
                        // Retain the existing Piper startup policy.
                        val startupDeadline = phrase.startupWaitMs
                        val remainingHeadroom = (startupDeadline - elapsedMs(start)).coerceAtLeast(0)
                        if (remainingHeadroom > 0) withTimeoutOrNull(remainingHeadroom) { startupReady.await() }
                        log("audio_startup_buffer targetMs=${phrase.startupWaitMs} pcmTargetMs=0 deadlineMs=$startupDeadline waitMs=${elapsedMs(start)}")
                        first = false
                        if (stopped) break
                    }
                    awaitPlaybackPermission()
                    if (stopped) break
                    val track = audioTrack ?: createTrack(phrase.sampleRate, phrase.pcm.size).also {
                        synchronized(playbackLock) { audioTrack = it }
                        // Native sample-rate playback: no time stretching or experimental pace.
                        synchronized(playbackLock) { if (!interrupted && !stopped) it.play() }
                        val startedTrack = it
                        // A sibling of the IO writer: its infinite loop must not block the writer returning.
                        playbackMonitor = speechScope.launch {
                            var previousUnderruns = startedTrack.underrunCount
                            var previousPoll = System.nanoTime() / 1_000_000
                            var wasStarved = false
                            while (isActive && !stopped) {
                                val head = unsignedHead(startedTrack)
                                speakerTimeline.observe(System.nanoTime() / 1_000_000, head)
                                val underruns = startedTrack.underrunCount
                                val poll = System.nanoTime() / 1_000_000
                                val starved = !draining.get() && !interrupted && !gapCuePlaying.get() && !sentenceRefilling.get() && writtenFrames > 0 && head >= writtenFrames
                                if (wasStarved && starved) playbackStarvationMs.addAndGet((poll - previousPoll).coerceAtLeast(0))
                                wasStarved = starved; previousPoll = poll
                                if (underruns > previousUnderruns) {
                                    log("audio_underrun count=$underruns queuedFrames=${(writtenFrames - head).coerceAtLeast(0)}")
                                    if (!draining.get() && !gapCuePlaying.get() && !sentenceRefilling.get()) streamingUnderruns.addAndGet((underruns - previousUnderruns).toLong())
                                    previousUnderruns = underruns
                                }
                                val audibleFrame = firstAudibleFrame.get()
                                if (audibleFrame >= 0 && head > audibleFrame && !playbackConfirmed) {
                                    playbackConfirmed = true
                                    firstTextToPlaybackMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                                    log("audio_playback_confirmed playbackHead=$head routeType=${startedTrack.routedDevice?.type} " +
                                        "routeId=${startedTrack.routedDevice?.id}")
                                    onChunkStarted("audio")
                                }
                                deliveryLedger?.advance(head.coerceAtMost(writtenFrames))
                                onPlayback(captions.at(head))
                                delay(if (playbackConfirmed) 40 else 10)
                            }
                        }
                    }
                    if (lastWriteAt != 0L) {
                        val gap = (elapsedMs(lastWriteAt) - lastQueuedMs - interveningCueMs).coerceAtLeast(0)
                        interveningCueMs = 0
                        estimatedGapMs += gap
                        if (gap > 50) log("audio_supply_gap index=${phrase.index} estimatedMs=$gap")
                    }
                    val queued = (framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)
                    log("audio_phrase_ready index=${phrase.index} pcmFrames=${phrase.pcm.size} " +
                        "queuedBeforeFrames=$queued underruns=${track.underrunCount}")
                    if (firstAudibleFrame.get() < 0) {
                        val audible = phrase.pcm.indexOfFirst { kotlin.math.abs(it.toInt()) >= 64 }
                        if (audible >= 0) firstAudibleFrame.set(framesWritten.toLong() + audible)
                    }
                    speakerTimeline.append(framesWritten.toLong(), phrase.pcm, phrase.sampleRate)
                    rememberPlayback(phrase.text)
                    captions.append(framesWritten.toLong(), phrase.sampleRate, phrase.pcm, phrase.text, phrase.captionGroup)
                    val start = System.nanoTime()
                    var offset = 0
                    var lastWriteProgress = playbackClock.nowMs()
                    while (offset < phrase.pcm.size && !stopped) {
                        ensureActive()
                        awaitPlaybackPermission()
                        if (stopped) break
                        val written = track.write(phrase.pcm, offset, phrase.pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { "AudioTrack rejected voice PCM output: $written" }
                        if (written == 0) {
                            check(playbackClock.nowMs() - lastWriteProgress < 5000) { "AudioTrack stopped accepting speech PCM." }
                            delay(10)
                            continue
                        }
                        lastWriteProgress = playbackClock.nowMs()
                        audioTrace?.append(phrase.pcm, offset, written, phrase.sampleRate)
                        offset += written
                        framesWritten += written
                        writtenFrames = framesWritten.toLong()
                    }
                    lastWriteAt = System.nanoTime()
                    lastQueuedMs = ((framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0) *
                        1000.0 / phrase.sampleRate / playbackSpeed).toLong()
                    log("audio_phrase_written index=${phrase.index} writeMs=${elapsedMs(start)} " +
                        "queuedAfterFrames=${(framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)} underruns=${track.underrunCount}")
                }
                producer.join()
                if (!stopped && framesWritten > 0) {
                    val track = requireNotNull(audioTrack)
                    // Older Android versions cannot lower the start threshold. A finite
                    // short stream must fill that threshold with silence to start at all.
                    val threshold = if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames else track.bufferSizeInFrames
                    val padding = (threshold - framesWritten).coerceAtLeast(0)
                    if (padding > 0 && unsignedHead(track) == 0L) {
                        log("audio_short_clip_padding frames=$padding")
                        val zeros = ShortArray(padding)
                        var offset = 0
                        val deadline = playbackClock.nowMs() + 3000
                        while (offset < zeros.size && !stopped) {
                            ensureActive()
                            awaitPlaybackPermission()
                            if (stopped) break
                            check(playbackClock.nowMs() < deadline) { "AudioTrack short-clip padding timed out." }
                            val written = track.write(zeros, offset, zeros.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0) { "AudioTrack rejected short-clip padding: $written" }
                            if (written == 0) delay(10) else offset += written
                        }
                    }
                    draining.set(true)
                    completed = drainAudioTrack(framesWritten, outputSampleRate, playbackSpeed)
                    if (completed) onPlaybackEnded()
                    if (!completed && !stopped) error("AudioTrack playback timed out before all speech was consumed.")
                }
                finalUnderruns = audioTrack?.underrunCount ?: 0
            }
        } catch (error: Throwable) {
            failureMessage = error.message ?: error.javaClass.simpleName
            if (error !is CancellationException) deliveryFailed.set(true)
            throw error
        } finally {
            val wasStopped = stopped
            stopped = true
            // Unblock the native producer waiting on a full queue before joining its native owner.
            withContext(NonCancellable) { acknowledgement.close() }
            audio.cancel()
            tokens.cancel()
            withContext(NonCancellable) { playbackMonitor?.cancelAndJoin() }
            val playedFrames = synchronized(playbackLock) {
                if (!completed) audioTrack?.let { runCatching { it.pause() } }
                maxOf(audioTrack?.let(::unsignedHead) ?: 0L, stoppedPlaybackHead.get())
            }
            deliveryLedger?.advance(playedFrames.coerceAtMost(writtenFrames),
                if (completed && !wasStopped && failureMessage == null) SpeechDeliveryState.COMPLETED
                else if (deliveryFailed.get()) SpeechDeliveryState.FAILED else SpeechDeliveryState.INTERRUPTED)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { audioTrace?.finish(playedFrames) }
                    .onFailure { log("speech_audio_trace_failed reason=${it.message}") }
            }
            onPlayback(if (wasStopped) VoicePlaybackFrame() else captions.at(playedFrames).copy(level = 0f))
            val outputRoute = audioTrack?.routedDevice?.let { "type=${it.type} id=${it.id}" }
            finalUnderruns = audioTrack?.underrunCount ?: finalUnderruns
            synchronized(playbackLock) {
                audioTrack?.stopSafely()
                audioTrack = null
            }
            withContext(NonCancellable) { collectTokens.cancelAndJoin(); producer.cancelAndJoin() }
            acknowledgementRequests.cancel()
            if (modelSession == null) nativeDispatcher.close()
            speaking.set(false)
            if (inputChars > 0 || failureMessage != null) runCatching {
                onMetrics(TtsSessionMetrics(loadMs, firstPcmMs, totalSynthesisMs, totalAudioMs,
                    totalQueueWaitMs, playbackSpeed, estimatedGapMs, finalUnderruns, phraseCount,
                    inputChars, textHash.digest().joinToString("") { "%02x".format(it) }, numThreads,
                    completed && !wasStopped && failureMessage == null, failureMessage,
                    playbackConfirmed, playedFrames, framesWritten.toLong(), outputRoute,
                    firstTextToPcmMs, firstTextToPlaybackMs, piperOpening,
                    0L, false, playbackStarvationMs.get(), sourcePcmSummary, pcmDelivery))
            }.onFailure { log("tts_metrics_failed reason=${it.message}") }
            log("tts_session_finished phrases=$phraseCount synthesisMs=$totalSynthesisMs " +
                "audioDurationMs=$totalAudioMs queueWaitMs=$totalQueueWaitMs playbackSpeed=$playbackSpeed " +
                "estimatedSupplyGapMs=$estimatedGapMs observedPlaybackStarvationMs=${playbackStarvationMs.get()} " +
                "realtimeFactor=${if (totalAudioMs > 0) totalSynthesisMs.toDouble() / totalAudioMs else 0.0}")
        }
    }

    override fun stopSpeaking() {
        stopped = true
        // Never release an AudioTrack while its writer is using it.
        synchronized(playbackLock) {
            audioTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { stoppedPlaybackHead.accumulateAndGet(unsignedHead(track)) { previous, current -> maxOf(previous, current) } }
                deliveryLedger?.advance(stoppedPlaybackHead.get().coerceAtMost(writtenFrames), SpeechDeliveryState.INTERRUPTED)
                runCatching { track.flush() }
            }
            deliveryLedger?.advance(stoppedPlaybackHead.get().coerceAtMost(writtenFrames), SpeechDeliveryState.INTERRUPTED)
        }
    }

    fun release() { stopSpeaking() }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun unsignedHead(track: AudioTrack) = track.playbackHeadPosition.toLong() and 0xffffffffL

    private suspend fun drainAudioTrack(framesWritten: Int, sampleRate: Int, speed: Float): Boolean {
        val track = audioTrack ?: return false
        log("audio_track_drain_started frames=$framesWritten sampleRate=$sampleRate state=${track.state} underruns=${track.underrunCount}")
        val drained = PlaybackDrain.await(framesWritten.toLong(), sampleRate, speed,
            head = { unsignedHead(track) }, stopped = { stopped }, nowMs = playbackClock::nowMs)
        log("audio_track_drain_finished playbackHead=${unsignedHead(track)} targetFrames=$framesWritten " +
            "drained=$drained stopped=$stopped underruns=${track.underrunCount}")
        return drained
    }

    private fun createTrack(sampleRate: Int, firstPhraseFrames: Int): AudioTrack {
        log("audio_track_create sampleRate=$sampleRate usage=${CallAudioRouting.usage}")
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Two seconds of device buffering complements the bounded PCM queue.
        // It absorbs scheduling jitter, but cannot compensate for sustained slow synthesis.
        val bufferSize = maxOf(minBuffer, sampleRate * 2 * 2)
        log("audio_track_buffer minBytes=$minBuffer selectedBytes=$bufferSize bufferMs=${bufferSize * 1_000L / (sampleRate * 2)}")
        return AudioTrack.Builder()
            .setAudioAttributes(
                    AudioAttributes.Builder()
                    .setUsage(CallAudioRouting.usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "AudioTrack could not initialize for Piper output."
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    val requested = PlaybackDrain.startThreshold(sampleRate, firstPhraseFrames)
                    val actual = track.setStartThresholdInFrames(requested)
                    log("audio_start_threshold requestedFrames=$requested actualFrames=$actual capacityFrames=${track.bufferCapacityInFrames}")
                } else {
                    log("audio_start_threshold legacy=true capacityFrames=${track.bufferCapacityInFrames}")
                }
                track.setVolume(1.0f)
                log("audio_track_ready state=${track.state} sampleRate=$sampleRate buffer=$bufferSize")
            }
    }

    private fun AudioTrack.stopSafely() {
        runCatching { pause() }
        runCatching { flush() }
        runCatching { release() }
    }
}
