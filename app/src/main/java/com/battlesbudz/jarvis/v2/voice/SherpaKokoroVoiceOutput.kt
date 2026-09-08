package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/** Local Jarvis voice output: Gemma text -> selected Sherpa model PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val engine: TtsEngine = TtsEngine.KOKORO,
    private val normalSpeed: Boolean = false,
    private val fixedChunking: Boolean = false,
    private val onPlayback: (VoicePlaybackFrame) -> Unit = {},
    private val onMetrics: (TtsSessionMetrics) -> Unit = {},
    private val speakerId: Int = engine.speaker,
    private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)
    private val playbackLock = Any()
    @Volatile private var interrupted = false
    private val playbackClock = PlaybackClock()

    fun setInterrupted(value: Boolean) = synchronized(playbackLock) {
        if (interrupted == value) return@synchronized
        interrupted = value
        playbackClock.setPaused(value)
        audioTrack?.let { track ->
            if (value) track.pause() else if (!stopped) track.play()
        }
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
        val playbackSpeed: Float
    )

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        val speechScope = this
        log("tts_session_started engine=${engine.id} modelDir=$modelDirectory speaker=$speakerId threads=$numThreads workers=1")
        var framesWritten = 0
        var outputSampleRate = 0
        var totalSynthesisMs = 0L
        var totalAudioMs = 0L
        var totalQueueWaitMs = 0L
        var phraseCount = 0
        var loadMs = 0L
        var firstPcmMs: Long? = null
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
        val chunker = SpeechChunker()
        val nativeDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "jarvis-tts").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val producer = launch(nativeDispatcher) {
            var engine: OfflineTts? = null
            var failure: Throwable? = null
            var index = 0
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val tts = OfflineTts(config = sherpaTtsConfig(this@SherpaKokoroVoiceOutput.engine, modelDirectory, numThreads))
                engine = tts
                loadMs = elapsedMs(loadStart)
                log("tts_engine_preload_finished loadMs=$loadMs")
                suspend fun generate(text: String) {
                    owner.ensureActive()
                    if (stopped) return
                    val phraseIndex = index++
                    val started = System.nanoTime()
                    log("tts_generation_started index=$phraseIndex chars=${text.length} preview=${text.take(80)} api=generateWithConfig")
                    // Use the established non-callback JNI path. Kotlin lambda callback ABI
                    // changes can abort the process before Java can report an exception.
                    val generated = tts.generateWithConfig(
                        text, GenerationConfig(silenceScale = 0.2f, sid = speakerId)
                    )
                    owner.ensureActive()
                    if (stopped) return
                    val rate = generated.sampleRate
                    check(rate > 0) { "Voice model returned an invalid sample rate." }
                    val pcm = ShortArray(generated.samples.size) { i ->
                        (generated.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    val peak = generated.samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                    val rms = kotlin.math.sqrt(generated.samples.sumOf { it.toDouble() * it } /
                        generated.samples.size.coerceAtLeast(1))
                    log("tts_pcm_level index=$phraseIndex rms=$rms peak=$peak frames=${pcm.size}")
                    val frames = pcm.size.toLong()
                    if (firstPcmMs == null) firstPcmMs = elapsedMs(started)
                    log("tts_first_audio index=$phraseIndex latencyMs=${elapsedMs(started)}")
                    val waitStart = System.nanoTime()
                    audio.sendFromNative(SynthesizedPhrase(phraseIndex, text, rate, pcm,
                        PlaybackBufferPolicy.startupWaitMs(elapsedMs(started), frames * 1000 / rate),
                        if (normalSpeed) 1f else PlaybackBufferPolicy.playbackSpeed(elapsedMs(started), frames * 1000 / rate)))
                    val queueWaitMs = elapsedMs(waitStart)
                    val totalMs = elapsedMs(started)
                    val synthesisMs = (totalMs - queueWaitMs).coerceAtLeast(0)
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
                chunks.collect { token ->
                    owner.ensureActive()
                    if (!stopped) {
                        inputChars += token.length
                        textHash.update(token.toByteArray(Charsets.UTF_8))
                        chunker.append(token)
                        while (true) generate(chunker.take() ?: break)
                    }
                }
                while (true) generate(chunker.take(final = true) ?: break)
            } catch (error: Throwable) {
                failureMessage = error.message ?: error.javaClass.simpleName
                failure = error
                throw error
            } finally {
                // Release only after generate has returned, including when cancellation was requested.
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
                        if (phrase.startupWaitMs > 0) withTimeoutOrNull(phrase.startupWaitMs) { producer.join() }
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
                            while (isActive && !stopped) {
                                val head = unsignedHead(startedTrack)
                                if (head > 0 && !playbackConfirmed) {
                                    playbackConfirmed = true
                                    log("audio_playback_confirmed playbackHead=$head routeType=${startedTrack.routedDevice?.type} " +
                                        "routeId=${startedTrack.routedDevice?.id}")
                                    onChunkStarted("audio")
                                }
                                onPlayback(captions.at(head))
                                delay(40)
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
                    captions.append(framesWritten.toLong(), phrase.sampleRate, phrase.pcm, phrase.text)
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
                        offset += written
                        framesWritten += written
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
            audio.cancel()
            withContext(NonCancellable) { playbackMonitor?.cancelAndJoin() }
            val playedFrames = audioTrack?.let(::unsignedHead) ?: 0L
            onPlayback(if (wasStopped) VoicePlaybackFrame() else captions.at(playedFrames).copy(level = 0f))
            val outputRoute = audioTrack?.routedDevice?.let { "type=${it.type} id=${it.id}" }
            finalUnderruns = audioTrack?.underrunCount ?: finalUnderruns
            synchronized(playbackLock) {
                audioTrack?.stopSafely()
                audioTrack = null
            }
            withContext(NonCancellable) { producer.cancelAndJoin() }
            nativeDispatcher.close()
            speaking.set(false)
            if (inputChars > 0 || failureMessage != null) runCatching {
                onMetrics(TtsSessionMetrics(loadMs, firstPcmMs, totalSynthesisMs, totalAudioMs,
                    totalQueueWaitMs, playbackSpeed, estimatedGapMs, finalUnderruns, phraseCount,
                    inputChars, textHash.digest().joinToString("") { "%02x".format(it) }, numThreads,
                    completed && !wasStopped && failureMessage == null, failureMessage,
                    playbackConfirmed, playedFrames, framesWritten.toLong(), outputRoute))
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
            audioTrack?.let { track -> runCatching { track.pause() }; runCatching { track.flush() } }
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
