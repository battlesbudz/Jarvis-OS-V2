Warning: truncated output (original token count: 12937)
Total output lines: 819

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
    // Shared by the producer and playback collector; a local capture was not
    // visible to all coroutine scopes on the CI Kotlin compiler.
    private val deliveryFailed = AtomicBoolean(false)
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
    /** Optional interruption ASR yields when answer audio cannot cover its work budget. */
    fun hasInterruptionBudget(): Boolean = synchronized(playbackLock) {
        if (stopped || interrupted || gapCuePlaying.get()) return@synchronized false
        val track = audioTrack ?: return@synchronized true
        val queued = (writtenFrames - unsignedHead(track)).coerceAtLeast(0)
        // Natural barge-in needs enough audio buffered to cover a bounded
        // Moonshine probe, but waiting for 900 ms left the recognizer starved
        // whenever TTS was synthesizing the next phrase.  The capture/VAD gate
        // still filters noise; this threshold only reserves play…9937 tokens truncated…unt ?: finalUnderruns
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
