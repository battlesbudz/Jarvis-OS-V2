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
    private val acknowledgeDelays: Boolean = false,
    private val playbackVolume: () -> String = { "unavailable" },
    private val audioTrace: SpeechAudioTrace? = null,
    private val onReady: () -> Unit = {},
    private val onPlayback: (VoicePlaybackFrame) -> Unit = {},
    private val onMetrics: (TtsSessionMetrics) -> Unit = {},
    private val speakerId: Int = engine.speaker,
    private val numThreads: Int = if (engine == TtsEngine.POCKET_PAUL) 2 else Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val playbackLock = Any()
    @Volatile private var interrupted = false
    private val playbackClock = PlaybackClock()
    private val spokenReference = StringBuilder()
    fun recentSpokenText(): String = synchronized(spokenReference) { spokenReference.toString() }
    @Volatile private var writtenFrames = 0L
    private var lastAudibleAt = Long.MIN_VALUE / 2
    private val preparedOpening = AtomicReference<PreparedSpeechOpening?>(null)
    private val openingRequests = Channel<PreparedSpeechOpening>(Channel.CONFLATED,
        onUndeliveredElement = { it.discard() })

    private val stoppedPlaybackHead = AtomicLong()
    private val acknowledgement = DelayedAcknowledgement(log)
    private val neutralFiller = FillerPhrases.INITIAL
    private val fillerDiskCache = FillerAudioCache(java.io.File(modelDirectory, "filler-cache-v2"))
    private val acknowledgementRequests = Channel<String>(Channel.CONFLATED)
    private fun fillerCacheKey(text: String) = "${engine.version}:$modelDirectory:$speakerId:$text"
    fun acknowledgeConfirmedTurn() {
        if (!acknowledgeDelays) return
        acknowledgement.request(neutralFiller)
        acknowledgementRequests.trySend(FillerPhrases.FOLLOWUP)
    }
    private companion object {
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
        audible || now - lastAudibleAt < 350 // Speaker/reverberation tail after drain.
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

    private data class SynthesizedPhrase(
        val index: Int,
        val text: String,
        val sampleRate: Int,
        val pcm: ShortArray,
        val startupWaitMs: Long,
        val playbackSpeed: Float,
        val captionGroup: Int? = null
    )

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        stoppedPlaybackHead.set(0)
        val speechScope = this
        if (acknowledgeDelays) {
            // Cached PCM needs no native model reload before it can be played.
            withContext(Dispatchers.IO) {
                for (text in listOf(neutralFiller, FillerPhrases.FOLLOWUP)) {
                    val key = fillerCacheKey(text)
                    (acknowledgementCache[key] ?: fillerDiskCache.read(key, text))?.let {
                        acknowledgementCache[key] = it
                        acknowledgement.prepare(it)
                        log("acknowledgement_cache_hit beforeModelLoad=true text=$text")
                    }
                }
            }
        }
        if (acknowledgeDelays) acknowledgement.start(this) { audio ->
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
        var finalUnderruns = 0
        var playbackSpeed = 1f
        var estimatedGapMs = 0L
        // One owner creates, invokes and releases the native engine. Playback never owns it.
        // Bounded PCM backpressure prevents long answers from accumulating unlimited audio.
        val audio = NativeAudioQueue<SynthesizedPhrase>(2)
        val pocketSentences = engine == TtsEngine.POCKET_PAUL && (benchmarkProfile?.nativeStreaming == true || !fixedChunking && benchmarkProfile == null)
        val pocketText = if (pocketSentences) PocketTextStream() else null
        val chunker = SpeechChunker(openingChars, fullText = benchmarkProfile?.fullText == true)
        val nativeSession = java.util.UUID.randomUUID().toString()
        val streamDiagnostics = if (engine == TtsEngine.POCKET_PAUL)
            PocketStreamDiagnostics(nativeSession, log) else null
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
        val nativeDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "jarvis-tts").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val producer = launch(nativeDispatcher) {
            var engine: OfflineTts? = null
            var failure: Throwable? = null
            var index = 0
            var previousChars = 0
            var previousSynthesisMs = 0L
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val tts = OfflineTts(config = sherpaTtsConfig(this@SherpaKokoroVoiceOutput.engine, modelDirectory, numThreads))
                engine = tts
                loadMs = elapsedMs(loadStart)
                log("tts_engine_preload_finished loadMs=$loadMs")
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
                    "referenceSha256=${PocketVoiceSpec.PAUL_SHA256} nativeContext=cached_voice_prompt decoderContext=continuous_per_answer " +
                    "pcmDelivery=interleaved_latent_decode firstAudioFrames=3 audioFramesPerChunk=5 session=$nativeSession")
                onReady()
                fun synthesize(text: String): SpeechAudio {
                    owner.ensureActive()
                    val started = System.nanoTime()
                    log("tts_generation_started chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
                    // Use the established non-callback JNI path. Kotlin lambda callback ABI
                    // changes can abort the process before Java can report an exception.
                    val generated = tts.generateWithConfig(
                        text, if (pocket && text in listOf(neutralFiller, FillerPhrases.FOLLOWUP))
                            generation.copy(extra = PocketSpeechPolicy.extra(filler = true))
                        else if (pocket) generation.copy(extra = PocketSpeechPolicy.extra()) else generation
                    )
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
                            FillerPcm.prepare(synthesize(text))
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
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        acknowledgement.preparationFailed(text)
                        log("acknowledgement_cache_unavailable reason=${error.message}")
                    }
                }
                if (acknowledgeDelays) prepareAcknowledgement(neutralFiller)
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
                        var callbackCount = 0
                        var frames = 0L
                        var queueWaitMs = 0L
                        streamDiagnostics?.begin(phraseIndex, text, rate)
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
                                    val waitStart = System.nanoTime()
                                    // One copy of each callback, never also enqueue the returned full utterance.
                                    // Captions remain estimated; the text belongs to the first audio chunk.
                                    audio.sendFromNative(SynthesizedPhrase(phraseIndex,
                                        if (callbackCount == 0) text else "", rate, pcm, 0,
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
                        val generated = tts.generateWithConfigAndCallback(text, generation, callback)
                        callback.failure?.let { throw it }
                        owner.ensureActive()
                        if (stopped) return
                        check(frames > 0 && frames == generated.samples.size.toLong() && generated.sampleRate == rate) {
                            "Pocket callback PCM did not match the generated utterance."
                        }
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
                                if (pocketText != null) {
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
                            if (index == 0 && acknowledgeDelays) prepareAcknowledgement(text)
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
                while (true) generate(nextPhrase(final = true) ?: break)
            } catch (error: Throwable) {
                failureMessage = error.message ?: error.javaClass.simpleName
                failure = error
                throw error
            } finally {
                // Release only after generate has returned, including when cancellation was requested.
                startupReady.complete(Unit)
                engine?.release()
                audio.close(failure)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                var first = true
                var lastWriteAt = 0L
                var lastQueuedMs = 0L
                for (phrase in audio.chunks) {
                    ensureActive()
                    if (stopped || phrase.pcm.isEmpty()) continue
                    outputSampleRate = phrase.sampleRate
                    if (first) {
                        // Small startup headroom; never hold a short, completed answer for this delay.
                        val start = System.nanoTime()
                        acknowledgement.answerReady(neutralFiller)
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
                                onPlayback(captions.at(head))
                                delay(if (playbackConfirmed) 40 else 10)
                            }
                        }
                    }
                    if (lastWriteAt != 0L) {
                        val gap = (elapsedMs(lastWriteAt) - lastQueuedMs).coerceAtLeast(0)
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
                    completed = drainAudioTrack(framesWritten, outputSampleRate, playbackSpeed)
                    if (!completed && !stopped) error("AudioTrack playback timed out before all speech was consumed.")
                }
                finalUnderruns = audioTrack?.underrunCount ?: 0
            }
        } catch (error: Throwable) {
            failureMessage = error.message ?: error.javaClass.simpleName
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
            val playedFrames = maxOf(audioTrack?.let(::unsignedHead) ?: 0L, stoppedPlaybackHead.get())
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
            streamDiagnostics?.summary(finalUnderruns, estimatedGapMs,
                completed && !wasStopped && failureMessage == null)
            acknowledgementRequests.cancel()
            openingRequests.cancel()
            nativeDispatcher.close()
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
                runCatching { track.flush() }
            }
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
