package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.io.File

/** Local Jarvis voice output: Gemma text -> Kokoro PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val speakerId: Int = 10,
    private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    @Volatile private var stopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private val speaking = AtomicBoolean(false)

    private data class SynthesizedPhrase(
        val index: Int,
        val sampleRate: Int,
        val pcm: ShortArray,
        val startupWaitMs: Long
    )

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        check(speaking.compareAndSet(false, true)) { "Voice output is already active." }
        stopped = false
        log("tts_session_started modelDir=$modelDirectory speaker=$speakerId threads=$numThreads workers=1")
        var framesWritten = 0
        var outputSampleRate = 0
        var totalSynthesisMs = 0L
        var totalAudioMs = 0L
        var totalQueueWaitMs = 0L
        var phraseCount = 0
        // One owner creates, invokes and releases the native engine. Playback never owns it.
        // Bounded PCM backpressure prevents long answers from accumulating unlimited audio.
        val audio = NativeAudioQueue<SynthesizedPhrase>(2)
        val chunker = SpeechChunker()
        val nativeDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "jarvis-kokoro").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val producer = launch(nativeDispatcher) {
            var engine: OfflineTts? = null
            var failure: Throwable? = null
            var index = 0
            val owner = currentCoroutineContext()
            try {
                val loadStart = System.nanoTime()
                log("tts_engine_preload_started")
                val tts = OfflineTts(config = config())
                engine = tts
                log("tts_engine_preload_finished loadMs=${elapsedMs(loadStart)}")
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
                    check(rate > 0) { "Kokoro returned an invalid sample rate." }
                    val pcm = ShortArray(generated.samples.size) { i ->
                        (generated.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    }
                    val frames = pcm.size.toLong()
                    log("tts_first_audio index=$phraseIndex latencyMs=${elapsedMs(started)}")
                    val waitStart = System.nanoTime()
                    audio.sendFromNative(SynthesizedPhrase(phraseIndex, rate, pcm,
                        PlaybackBufferPolicy.startupWaitMs(elapsedMs(started), frames * 1000 / rate)))
                    val queueWaitMs = elapsedMs(waitStart)
                    val totalMs = elapsedMs(started)
                    val synthesisMs = (totalMs - queueWaitMs).coerceAtLeast(0)
                    val audioMs = frames * 1000 / rate
                    val rtf = if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0
                    totalSynthesisMs += synthesisMs
                    totalAudioMs += audioMs
                    totalQueueWaitMs += queueWaitMs
                    phraseCount++
                    chunker.observe(rtf)
                    log("tts_generation_finished index=$phraseIndex synthesisMs=$synthesisMs queueWaitMs=$queueWaitMs " +
                        "audioDurationMs=$audioMs realtimeFactor=$rtf")
                }
                chunks.collect { token ->
                    owner.ensureActive()
                    if (!stopped) {
                        chunker.append(token)
                        while (true) generate(chunker.take() ?: break)
                    }
                }
                while (true) generate(chunker.take(final = true) ?: break)
            } catch (error: Throwable) {
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
                    val track = audioTrack ?: createTrack(phrase.sampleRate).also { audioTrack = it; it.play() }
                    onChunkStarted("audio")
                    val queued = (framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)
                    log("audio_phrase_ready index=${phrase.index} pcmFrames=${phrase.pcm.size} " +
                        "queuedBeforeFrames=$queued underruns=${track.underrunCount}")
                    val start = System.nanoTime()
                    var offset = 0
                    while (offset < phrase.pcm.size && !stopped) {
                        ensureActive()
                        val written = track.write(phrase.pcm, offset, phrase.pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(written >= 0) { "AudioTrack rejected Kokoro PCM output: $written" }
                        if (written == 0) { delay(10); continue }
                        offset += written
                        framesWritten += written
                    }
                    log("audio_phrase_written index=${phrase.index} writeMs=${elapsedMs(start)} " +
                        "queuedAfterFrames=${(framesWritten.toLong() - unsignedHead(track)).coerceAtLeast(0)} underruns=${track.underrunCount}")
                }
                producer.join()
                if (!stopped && framesWritten > 0) drainAudioTrack(framesWritten, outputSampleRate)
            }
        } finally {
            stopped = true
            // Unblock a callback waiting on a full queue before joining its native owner.
            audio.cancel()
            audioTrack?.stopSafely()
            audioTrack = null
            withContext(NonCancellable) { producer.cancelAndJoin() }
            nativeDispatcher.close()
            speaking.set(false)
            log("tts_session_finished phrases=$phraseCount synthesisMs=$totalSynthesisMs " +
                "audioDurationMs=$totalAudioMs queueWaitMs=$totalQueueWaitMs " +
                "realtimeFactor=${if (totalAudioMs > 0) totalSynthesisMs.toDouble() / totalAudioMs else 0.0}")
        }
    }

    override fun stopSpeaking() {
        stopped = true
        // Never release an AudioTrack while its writer is using it.
        audioTrack?.let { track -> runCatching { track.pause() }; runCatching { track.flush() } }
    }

    fun release() { stopSpeaking() }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun unsignedHead(track: AudioTrack) = track.playbackHeadPosition.toLong() and 0xffffffffL

    private suspend fun drainAudioTrack(framesWritten: Int, sampleRate: Int) {
        val track = audioTrack ?: return
        val deadline = System.currentTimeMillis() +
            (framesWritten * 1_000L / sampleRate).coerceAtLeast(1_000L) + 2_000L
        log("audio_track_drain_started frames=$framesWritten sampleRate=$sampleRate state=${track.state} underruns=${track.underrunCount}")
        while (!stopped && System.currentTimeMillis() < deadline) {
            if (unsignedHead(track) >= framesWritten.toLong()) break
            delay(20L)
        }
        log("audio_track_drain_finished playbackHead=${track.playbackHeadPosition} stopped=$stopped underruns=${track.underrunCount}")
    }

    private fun config() = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = "$modelDirectory/model.onnx",
                voices = "$modelDirectory/voices.bin",
                tokens = "$modelDirectory/tokens.txt",
                dataDir = "$modelDirectory/espeak-ng-data",
                // The official kokoro-en-v0_19 bundle does not include a
                // separate lexicon file. Its bundled espeak-ng data handles
                // English pronunciation, so leave lexicon empty when absent.
                lexicon = File(modelDirectory, "lexicon-us-en.txt")
                    .takeIf { it.isFile }
                    ?.path
                    .orEmpty(),
                lang = "en-us"
            ),
            numThreads = numThreads,
            debug = false,
            provider = "cpu"
        ),
        maxNumSentences = 1,
        silenceScale = 0.2f
    )

    private fun createTrack(sampleRate: Int): AudioTrack {
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
