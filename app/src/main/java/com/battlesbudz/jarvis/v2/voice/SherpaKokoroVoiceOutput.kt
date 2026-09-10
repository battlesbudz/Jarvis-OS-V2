package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

/** Local Jarvis voice output: Gemma text -> selected Sherpa model PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val engine: TtsEngine = TtsEngine.KOKORO,
    private val normalSpeed: Boolean = false,
    private val fixedChunking: Boolean = false,
    private val openingChars: Int = SpeechChunker.DEFAULT_OPENING_CHARS,
    private val benchmarkProfile: TtsBenchmarkProfile? = null,
    private val benchmarkRun: Boolean = false,
    private val modelSession: VoiceModelSession? = null,
    private val deliveryLedger: SpeechDeliveryLedger? = null,
    private val onPlaybackEnded: () -> Unit = {},
    private val benchmarkSubmissions: List<String>? = null,
    private val acknowledgeDelays: Boolean = false,
    private val openingPcm: ShortArray? = null,
    private val playbackVolume: () -> String = { "unavailable" },
    private val audioTrace: SpeechAudioTrace? = null,
    private val onReady: () -> Unit = {},
    private val onPlayback: (VoicePlaybackFrame) -> Unit = {},
    private val onMetrics: (TtsSessionMetrics) -> Unit = {},
    private val speakerId: Int = engine.speaker,
    private val numThreads: Int = if (engine == TtsEngine.POCKET_PAUL) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    init {
        require(benchmarkSubmissions == null || (benchmarkRun && engine == TtsEngine.POCKET_PAUL &&
            benchmarkProfile?.nativeStreaming == true && benchmarkSubmissions.isNotEmpty() &&
            benchmarkSubmissions.all { it.isNotBlank() }))
    }
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val gapCuePlaying = AtomicBoolean(false)
    private val playbackLock = Any()
    @Volatile private var interrupted = false
    private val playbackClock = PlaybackClock()
    private val spokenReference = StringBuilder()
    fun recentSpokenText(): String = synchronized(spokenReference) { spokenReference.toString() }
    @Volatile private var writtenFrames = 0L
    @Volatile private var lastAudibleAt = Long.MIN_VALUE / 2
    private val preparedOpening = AtomicReference<PreparedSpeechOpening?>(null)
    private val openingRequests = Channel<PreparedSpeechOpening>(Channel.CONFLATED,
        onUndeliveredElement = { it.discard() })

    private val stoppedPlaybackHead = AtomicLong()
    private val acknowledgement = DelayedAcknowledgement(log)
    private val neutralFiller = FillerPhrases.INITIAL
    private val fillerDiskCache = FillerAudioCache(java.io.File(modelDirectory, "filler-cache-v3"))
    private val acknowledgementRequests = Channel<String>(Channel.CONFLATED)
    private fun fillerCacheKey(text: String) = "opening-v5:${engine.version}:$modelDirectory:$speakerId:$text" +
        if (engine == TtsEngine.POCKET_PAUL) ":period=${benchmarkProfile?.leadingPeriod ?: false}" else ""
    internal fun updateWaitStage(stage: DelayedAcknowledgement.Stage) { acknowledgement.updateStage(stage) }
    fun acknowledgeConfirmedTurn() {
        if (!acknowledgeDelays) return
        acknowledgement.request(neutralFiller)
    }
    private companion object {
        val paulBuffer = PaulPlaybackBuffer()
        val acknowledgementCache = java.util.concurrent.ConcurrentHashMap<String, SpeechAudio>()
    }

    /** Queues silent work on the SAME native owner used for live speech. */
    fun prepareOpening(text: String): PreparedSpeechOpening? {
        // A detached pre-generated Paul opening would lose the continuing native state.
        if (benchmarkProfile != null || engine == TtsEngine.POCKET_PAUL || stopped || text.isBlank() || text.length > 240) return null
        val request = PreparedSpeechOpening(text)
        preparedOpening.getAndSet(request)?.discard()
        if (!openingRequests.trySend(request).isSuccess) { request.discard(); return null }
        return request
    }
    val isPlayingAudio: Boolean get() = synchronized(playbackLock) {
        val now = System.nanoTime() / 1_000_000
        val audible = !stopped && !interrupted && audioTrack?.let {
            it.playState == AudioTrack.PLAYSTATE_PLAYING && unsignedHead(it) < writtenFrames
        } == true
        if (audible) lastAudibleAt = now
        gapCuePlaying.get() || audible || now - lastAudibleAt < 350 // Speaker/reverberation tail after drain.
    }
    private fun applyPause() {
        val paused = interrupted
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
        val playbackSpeed: Float,
        val captionGroup: Int? = null,
        val sentenceEnd: Boolean = false
    )

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        stoppedPlaybackHead.set(0)
        val speechScope = this
        if (acknowledgeDelays) {
            // Cached PCM needs no native model reload before it can be played.
            withContext(Dispatchers.IO) {
                for (text in (listOf(neutralFiller) + FillerPhrases.VARIATIONS + DelayedAcknowledgement.Stage.entries.mapNotNull { it.cue })) {
                    val key = fillerCacheKey(text)
                    (if (text == neutralFiller && openingPcm != null) SpeechAudio(text, 24000, openingPcm, 0)
                    else acknowledgementCache[key] ?: fillerDiskCache.read(key, text))?.let {
                        acknowledgementCache[key] = it
                        acknowledgement.prepare(it)
                        log("acknowledgement_cache_hit beforeModelLoad=true text=$text source=${if (text == neutralFiller && openingPcm != null) "bundled_paul_umm_v1" else "generated_cache"}")
                    }
                }
            }
        }
        if (acknowledgeDelays) acknowledgement.start(this, requestPreparation = { acknowledgementRequests.trySend(it) }) { audio ->
            VoiceCues.playAcknowledgement(audio, { stopped }, { interrupted }, log, playbackVolume())
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
        var preparedSynthesisMs = 0L
        var preparedOpeningReused = false
        var inputChars = 0
        val textHash = java.security.MessageDigest.getInstance("SHA-256")
        val captions = SpokenCaptionTimeline()
        var playbackMonitor: Job? = null
        var playbackConfirmed = false
        var completed = false
        var failureMessage: String? = null
        val deliveryFailed = AtomicBoolean(false)
        var finalUnderruns = 0
        val streamingUnderruns = AtomicLong(0)
        val draining = AtomicBoolean(false)
        var playbackSpeed = 1f
        var estimatedGapMs = 0L
        // One owner creates, invokes and releases the native engine. Playback never owns it.
        // Bounded PCM backpressure prevents long answers from accumulating unlimited audio.
        val audio = NativeAudioQueue<SynthesizedPhrase>(2)
        val pocketSentences = engine == TtsEngine.POCKET_PAUL && (benchmarkProfile?.nativeStreaming == true || !fixedChunking && benchmarkProfile == null)
        val pocketText = if (pocketSentences) PocketTextStream() else null
        val isolationText = if (benchmarkSubmissions != null) StringBuilder() else null
        val chunker = SpeechChunker(openingChars, fullText = benchmarkProfile?.fullText == true)
        val paulReset = benchmarkProfile?.resetDecoder ?: true
        val paulPeriod = benchmarkProfile?.leadingPeriod ?: false
        val paulBaseBuffer = if (pocketSentences) benchmarkProfile?.bufferMs ?: 200 else 0
        val paulBufferMs = if (benchmarkRun) paulBaseBuffer else paulBuffer.target(paulBaseBuffer)
        val nativeSession = java.util.UUID.randomUUID().toString()
        val streamDiagnostics = if (engine == TtsEngine.POCKET_PAUL)
            PocketStreamDiagnostics(nativeSession, log, analyzeSource = benchmarkRun) else null
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
            var previousChars = 0
            var previousSynthesisMs = 0L
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val modelKey = "${this@SherpaKokoroVoiceOutput.engine.id}:$modelDirectory:$numThreads"
                engineLease = modelSession?.tts?.acquire(modelKey) {
                    OfflineTts(config = sherpaTtsConfig(this@SherpaKokoroVoiceOutput.engine, modelDirectory, numThreads))
                }
                val tts = engineLease?.value ?: OfflineTts(config =
                    sherpaTtsConfig(this@SherpaKokoroVoiceOutput.engine, modelDirectory, numThreads))
                engine = tts
                loadMs = elapsedMs(loadStart)
                log("tts_engine_preload_finished loadMs=$loadMs reused=${engineLease?.reused == true}")
                val pocket = this@SherpaKokoroVoiceOutput.engine == TtsEngine.POCKET_PAUL
                val generation = if (pocket) {
                    val reference = WaveReader.readWave("$modelDirectory/${PocketVoiceSpec.PAUL_FILE}")
                    check(reference.sampleRate > 0 && reference.samples.isNotEmpty() && reference.samples.all { it.isFinite() }) {
                        "Paul's reference audio could not be loaded."
                    }
                    log("tts_voice_reference voice=Paul speaker=p259 frames=${reference.samples.size} sampleRate=${reference.sampleRate}")
                    GenerationConfig(silenceScale = 1f, referenceAudio = reference.samples,
                        referenceSampleRate = reference.sampleRate, numSteps = 5,
                        extra = PocketSpeechPolicy.extra(session = nativeSession))
                } else GenerationConfig(silenceScale = 0.2f, sid = speakerId)
                if (pocket && benchmarkProfile?.fullText == true) log("pocket_voice_policy version=${PocketSpeechPolicy.VERSION} pcmDelivery=buffered_full_text nativeContext=isolated_baseline")
                else if (pocket) log("pocket_voice_policy version=${PocketSpeechPolicy.VERSION} " +
                    "seed=${PocketSpeechPolicy.SEED} temperature=0.7 steps=5 naturalSentenceInput=$pocketSentences " +
                    "referenceSha256=${PocketVoiceSpec.PAUL_SHA256} nativeContext=cached_voice_prompt decoderContext=${if (paulReset) "fresh_per_submission" else "continuous_per_answer"} " +
                    "pcmDelivery=interleaved_latent_decode firstAudioFrames=3 audioFramesPerChunk=5 session=$nativeSession " +
                    "leadingPeriod=$paulPeriod startupCushionMs=$paulBufferMs textBoundary=${if (pocketSentences) "sentence_group" else "benchmark_char_target"}")
                onReady()
                fun synthesize(text: String, optionalFiller: Boolean = false): SpeechAudio {
                    owner.ensureActive()
                    val started = System.nanoTime()
                    log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
                    val input = if (pocket) PocketSpeechPolicy.input(text, paulPeriod) else text
                    val config = if (pocket && optionalFiller) generation.copy(extra =
                        PocketSpeechPolicy.extra(filler = true) + mapOf("jarvis_session" to "$nativeSession/filler/${java.util.UUID.randomUUID()}"))
                        else if (pocket) generation.copy(extra = PocketSpeechPolicy.extra()) else generation
                    val generated = if (optionalFiller) {
                        // Stable Java callback; no PCM is played or cached until this preparation completes.
                        val callback = SherpaPcmCallback {
                            if (stopped || !owner.isActive || firstTextAt.get() != 0L) 0 else 1
                        }
                        val result = tts.generateWithConfigAndCallback(input, config, callback)
                        callback.failure?.let { throw it }
                        owner.ensureActive()
                        if (stopped || firstTextAt.get() != 0L) throw FillerSuperseded()
                        result
                    } else tts.generateWithConfig(input, config)
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
                    val candidate = preparedOpening.getAndSet(null)
                    val cached = if (index == 0) candidate?.takeFor(text) else null
                    candidate?.discard()
                    if (pocket && cached == null && benchmarkProfile?.fullText != true) {
                        val phraseIndex = index++
                        val rate = tts.sampleRate()
                        check(rate > 0)
                        val started = System.nanoTime()
                        val parity = if (benchmarkRun) CallbackPcmParity() else null
                        var callbackCount = 0
                        var frames = 0L
                        var queueWaitMs = 0L
                        val segmentSession = PocketSpeechPolicy.sessionId(nativeSession, phraseIndex, paulReset)
                        streamDiagnostics?.begin(phraseIndex, text, rate, segmentSession, paulReset)
                        log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfigAndCallback voice=Paul")
                        val callback = SherpaPcmCallback { samples ->
                            owner.ensureActive()
                            if (stopped) 0 else {
                                if (samples.isNotEmpty()) {
                                    check(samples.all { it.isFinite() }) { "Pocket returned non-finite PCM." }
                                    if (callbackCount == 0) {
                                        val latency = elapsedMs(started)
                                        log("tts_first_callback index=$phraseIndex latencyMs=$latency")
                                        if (phraseIndex == 0) {
                                            firstPcmMs = latency
                                            firstTextToPcmMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                                            log("tts_opening_ready prepared=false firstTextToPcmMs=$firstTextToPcmMs textBoundary=${if (pocketSentences) "sentence" else "benchmark"} nativeSession=$nativeSession")
                                        }
                                    }
                                    val pcm = ShortArray(samples.size) { i ->
                                        (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                                    }
                                    parity?.append(samples)
                                    val waitStart = System.nanoTime()
                                    // One copy of each callback, never also enqueue the returned full utterance.
                                    // Captions remain estimated; the text belongs to the first audio chunk.
                                    deliveryLedger?.append(phraseIndex, text, pcm.size, rate)
                                    audio.sendFromNative(SynthesizedPhrase(phraseIndex,
                                        if (callbackCount == 0) text else "", rate, pcm, if (phraseIndex == 0 && callbackCount == 0) paulBufferMs.toLong() else 0,
                                        benchmarkProfile?.playbackSpeed ?: 1f, captionGroup = phraseIndex))
                                    queueWaitMs += elapsedMs(waitStart)
                                    streamDiagnostics?.chunk(phraseIndex, pcm)
                                    frames += pcm.size
                                    callbackCount++
                                    log("tts_pcm_chunk index=$phraseIndex chunk=$callbackCount frames=${pcm.size} " +
                                        "peak=${samples.maxOf { kotlin.math.abs(it) }} nonFinite=0")
                                }
                                1
                            }
                        }
                        val generated = tts.generateWithConfigAndCallback(
                            PocketSpeechPolicy.input(text, paulPeriod),
                            generation.copy(extra = PocketSpeechPolicy.extra(session = segmentSession)), callback)
                        callback.failure?.let { throw it }
                        owner.ensureActive()
                        if (stopped) return
                        check(frames > 0 && frames == generated.samples.size.toLong() && generated.sampleRate == rate) {
                            "Pocket callback PCM did not match the generated utterance."
                        }
                        parity?.finish(generated.samples)?.let { result ->
                            log("pocket_stream_trace event=pcm_parity session=$nativeSession index=$phraseIndex " +
                                "matches=${result.matches} frames=${result.frames} method=sha256_float32_le " +
                                "callbackSha256=${result.callbackHash} returnedSha256=${result.returnedHash}")
                            check(result.matches) { "Pocket callback samples differ from returned audio." }
                        }
                        if (acknowledgeDelays && !benchmarkRun) {
                            val boundaryWait = System.nanoTime()
                            audio.sendFromNative(SynthesizedPhrase(phraseIndex, "", rate, ShortArray(0),
                                0, 1f, sentenceEnd = true))
                            queueWaitMs += elapsedMs(boundaryWait)
                        }
                        deliveryLedger?.seal(phraseIndex)
                        captions.complete(phraseIndex, frames)
                        streamDiagnostics?.finish(phraseIndex)
                        val synthesisMs = (elapsedMs(started) - queueWaitMs).coerceAtLeast(0)
                        val audioMs = frames * 1000 / rate
                        totalSynthesisMs += synthesisMs
                        totalAudioMs += audioMs
                        totalQueueWaitMs += queueWaitMs
                        phraseCount++
                        previousChars = text.length
                        previousSynthesisMs = synthesisMs
                        startupReady.complete(Unit)
                        val rtf = if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0
                        if (!fixedChunking) chunker.observe(rtf)
                        log("tts_generation_finished index=$phraseIndex synthesisMs=$synthesisMs queueWaitMs=$queueWaitMs " +
                            "audioDurationMs=$audioMs realtimeFactor=$rtf callbacks=$callbackCount")
                        return
                    }
                    val result = cached ?: synthesize(text)
                    if (stopped) return
                    val phraseIndex = index++
                    if (phraseIndex == 0) {
                        preparedOpeningReused = cached != null
                        firstPcmMs = result.synthesisMs
                        firstTextToPcmMs = firstTextAt.get().takeIf { it != 0L }?.let(::elapsedMs)
                        log("tts_opening_ready prepared=${cached != null} firstTextToPcmMs=$firstTextToPcmMs openingChars=$openingChars")
                    }
                    val rate = result.sampleRate
                    val frames = result.pcm.size.toLong()
                    val waitStart = System.nanoTime()
                    deliveryLedger?.append(phraseIndex, text, result.pcm.size, rate)
                    deliveryLedger?.seal(phraseIndex)
                    audio.sendFromNative(SynthesizedPhrase(phraseIndex, text, rate, result.pcm,
                        if (pocket || benchmarkProfile != null) 0 else PlaybackBufferPolicy.startupWaitMs(result.synthesisMs, frames * 1000 / rate),
                        benchmarkProfile?.playbackSpeed ?: if (normalSpeed || pocket) 1f else PlaybackBufferPolicy.playbackSpeed(result.synthesisMs, frames * 1000 / rate)))
                    if (phraseIndex == 1) startupReady.complete(Unit)
                    previousChars = text.length
                    previousSynthesisMs = result.synthesisMs
                    val queueWaitMs = elapsedMs(waitStart)
                    val synthesisMs = result.synthesisMs
                    val audioMs = frames * 1000 / rate
                    val rtf = if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0
                    totalSynthesisMs += synthesisMs
                    totalAudioMs += audioMs
                    totalQueueWaitMs += queueWaitMs
                    phraseCount++
                    if (!fixedChunking) chunker.observe(rtf)
                    log("tts_generation_finished index=$phraseIndex synthesisMs=$synthesisMs queueWaitMs=$queueWaitMs " +
                        "audioDurationMs=$audioMs realtimeFactor=$rtf")
                }
                fun nextPhrase(final: Boolean = false): String? {
                    if (pocketText != null) return pocketText.take(final)
                    if (fixedChunking || index == 0) return chunker.take(final)
                    val playedMs = synchronized(playbackLock) {
                        audioTrack?.let { unsignedHead(it) * 1000 / it.sampleRate } ?: 0L
                    }
                    val queuedMs = (totalAudioMs - playedMs).coerceAtLeast(0)
                    val limit = PlaybackBufferPolicy.nextChunkChars(queuedMs, previousChars, previousSynthesisMs)
                    return chunker.take(final, maxChars = limit)?.also {
                        log("audio_chunk_budget queuedMs=$queuedMs maxChars=$limit selectedChars=${it.length}")
                    }
                }
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
                                if (isolationText != null) {
                                    isolationText.append(token)
                                } else if (pocketText != null) {
                                    pocketText.append(token)
                                    // While native PCM was playing, Gemma may have completed more
                                    // text. Condition all ready sentences together; don't restart
                                    // synthesis for each token/sentence queued during backpressure.
                                    while (true) {
                                        val queued = tokens.tryReceive()
                                        queued.exceptionOrNull()?.let { throw it }
                                        val more = queued.getOrNull() ?: break
                                        inputChars += more.length
                                        textHash.update(more.toByteArray(Charsets.UTF_8))
                                        pocketText.append(more)
                                    }
                                } else chunker.append(token)
                                while (true) generate(nextPhrase() ?: break)
                            }
                        }
                        acknowledgementRequests.onReceive { text ->
                            // Native owns one stream, not a session map: never replace an active answer with filler.
                            if (index == 0 && acknowledgeDelays && firstTextAt.get() == 0L) prepareAcknowledgement(text)
                        }
                        openingRequests.onReceive { request ->
                            if (index == 0 && !request.isDiscarded() && preparedOpening.get() === request) {
                                log("tts_opening_preparation_started chars=${request.text.length}")
                                try {
                                    val result = synthesize(request.text)
                                    preparedSynthesisMs += result.synthesisMs
                                    request.complete(result)
                                    log("tts_opening_preparation_finished synthesisMs=${result.synthesisMs} discarded=${request.isDiscarded()}")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    request.discard()
                                    log("tts_opening_preparation_failed reason=${error.message}")
                                }
                            } else request.discard()
                        }
                    }
                }
                if (benchmarkSubmissions != null && !stopped) {
                    check(isolationText.toString() == benchmarkSubmissions.joinToString(" ")) {
                        "Diagnostic submissions must cover the exact input text."
                    }
                    benchmarkSubmissions.forEach { generate(it) }
                } else while (true) generate(nextPhrase(final = true) ?: break)
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
                val gapWaiter = SentenceGapWaiter()
                val gapAudio = acknowledgementCache[fillerCacheKey(neutralFiller)]
                while (true) {
                    val received = if (atSentenceBoundary && acknowledgeDelays && !benchmarkRun && gapAudio != null) {
                        gapWaiter.receive(audio.chunks, boundaryDrained = {
                            !stopped && !interrupted && audioTrack?.let { unsignedHead(it) >= writtenFrames } == true
                        }) {
                            val cueStarted = System.nanoTime()
                            gapCuePlaying.set(true)
                            log("sentence_gap_filler text=${gapAudio.text} boundary=completed_sentence excludes=answer_pcm")
                            try {
                                VoiceCues.playAcknowledgement(gapAudio, { stopped }, { interrupted }, log, playbackVolume())
                            } finally {
                                interveningCueMs += elapsedMs(cueStarted)
                                gapCuePlaying.set(false)
                                lastAudibleAt = System.nanoTime() / 1_000_000
                                log("sentence_gap_filler_finished answer_priority=true")
                            }
                        }
                    } else audio.chunks.receiveCatching()
                    received.exceptionOrNull()?.let { throw it }
                    val phrase = received.getOrNull() ?: break
                    if (phrase.sentenceEnd) { atSentenceBoundary = true; continue }
                    atSentenceBoundary = false
                    ensureActive()
                    if (stopped || phrase.pcm.isEmpty()) continue
                    outputSampleRate = phrase.sampleRate
                    if (first) {
                        // Small startup headroom; never hold a short, completed answer for this delay.
                        val start = System.nanoTime()
                        acknowledgement.answerReady()
                        val remainingHeadroom = (phrase.startupWaitMs - elapsedMs(start)).coerceAtLeast(0)
                        if (remainingHeadroom > 0) withTimeoutOrNull(remainingHeadroom) { startupReady.await() }
                        log("audio_startup_buffer targetMs=${phrase.startupWaitMs} waitMs=${elapsedMs(start)}")
                        first = false
                        if (stopped) break
                    }
                    awaitPlaybackPermission()
                    if (stopped) break
                    val track = audioTrack ?: createTrack(phrase.sampleRate, phrase.pcm.size).also {
                        synchronized(playbackLock) { audioTrack = it }
                        // Stretch existing PCM instead of asking Kokoro to synthesize more samples.
                        // Unsupported device routes retain normal-speed playback.
                        runCatching {
                            it.playbackParams = PlaybackParams().allowDefaults()
                                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                                .setPitch(1f).setSpeed(phrase.playbackSpeed)
                        }.onFailure { error -> log("audio_pace_fallback reason=${error.message}") }
                        playbackSpeed = it.playbackParams.speed
                        log("audio_playback_pace speed=$playbackSpeed pitch=1.0")
                        synchronized(playbackLock) { if (!interrupted && !stopped) it.play() }
                        val startedTrack = it
                        // A sibling of the IO writer: its infinite loop must not block the writer returning.
                        playbackMonitor = speechScope.launch {
                            var previousUnderruns = startedTrack.underrunCount
                            while (isActive && !stopped) {
                                val head = unsignedHead(startedTrack)
                                val underruns = startedTrack.underrunCount
                                if (underruns > previousUnderruns) {
                                    log("audio_underrun count=$underruns queuedFrames=${(writtenFrames - head).coerceAtLeast(0)}")
                                    if (!draining.get()) streamingUnderruns.addAndGet((underruns - previousUnderruns).toLong())
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
                    synchronized(spokenReference) {
                        spokenReference.append(" ").append(phrase.text)
                        if (spokenReference.length > 1600) spokenReference.delete(0, spokenReference.length - 1600)
                    }
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
            preparedOpening.getAndSet(null)?.discard()
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
            if (engine == TtsEngine.POCKET_PAUL && !benchmarkRun && completed && !wasStopped && paulBaseBuffer > 0)
                paulBuffer.observe(streamingUnderruns.get() > 0 || estimatedGapMs > 50)
            streamDiagnostics?.summary(finalUnderruns, estimatedGapMs,
                completed && !wasStopped && failureMessage == null)
            acknowledgementRequests.cancel()
            openingRequests.cancel()
            if (modelSession == null) nativeDispatcher.close()
            speaking.set(false)
            if (inputChars > 0 || failureMessage != null) runCatching {
                onMetrics(TtsSessionMetrics(loadMs, firstPcmMs, totalSynthesisMs, totalAudioMs,
                    totalQueueWaitMs, playbackSpeed, estimatedGapMs, finalUnderruns, phraseCount,
                    inputChars, textHash.digest().joinToString("") { "%02x".format(it) }, numThreads,
                    completed && !wasStopped && failureMessage == null, failureMessage,
                    playbackConfirmed, playedFrames, framesWritten.toLong(), outputRoute,
                    firstTextToPcmMs, firstTextToPlaybackMs, if (pocketSentences || benchmarkProfile?.fullText == true) null else openingChars,
                    preparedSynthesisMs, preparedOpeningReused))
            }.onFailure { log("tts_metrics_failed reason=${it.message}") }
            log("tts_session_finished phrases=$phraseCount synthesisMs=$totalSynthesisMs " +
                "audioDurationMs=$totalAudioMs queueWaitMs=$totalQueueWaitMs playbackSpeed=$playbackSpeed " +
                "estimatedSupplyGapMs=$estimatedGapMs " +
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
        log("audio_track_create sampleRate=$sampleRate")
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
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    "AudioTrack could not initialize for Kokoro output."
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
