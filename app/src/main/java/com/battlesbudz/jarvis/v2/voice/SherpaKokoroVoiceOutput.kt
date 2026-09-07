package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Local Jarvis voice output: Gemma text -> Kokoro PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val speakerId: Int = 10,
    private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    @Volatile private var stopped = false
    private var audioTrack: AudioTrack? = null
    private var preloadedEngine: OfflineTts? = null

    private data class SynthesizedPhrase(
        val index: Int,
        val sampleRate: Int,
        val pcm: ShortArray
    )

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) = coroutineScope {
        stopped = false
        log("tts_session_started modelDir=$modelDirectory speaker=$speakerId threads=$numThreads")
        var framesWritten = 0
        var outputSampleRate = 0
        val phraseQueue = Channel<kotlinx.coroutines.Deferred<SynthesizedPhrase>>(Channel.UNLIMITED)
        val synthesisSlots = Semaphore(2)
        val producer = launch(Dispatchers.Default) {
            val buffer = StringBuilder()
            var nextPhraseIndex = 0
            try {
                log("tts_engine_preload_started")
                preloadedEngine = OfflineTts(config = config())
                log("tts_engine_preload_finished")
                suspend fun enqueuePhrase(phrase: String) {
                    if (phrase.isBlank() || stopped) return
                    val phraseIndex = nextPhraseIndex++
                    onChunkStarted(phrase)
                    phraseQueue.send(async(Dispatchers.Default) {
                        synthesisSlots.withPermit { synthesize(phraseIndex, phrase) }
                    })
                }
                chunks.collect { token ->
                    if (stopped) return@collect
                    buffer.append(token)
                    while (true) {
                        val boundary = sentenceBoundary(buffer)
                        if (boundary <= 0) break
                        val phrase = buffer.substring(0, boundary).trim()
                        buffer.delete(0, boundary)
                        enqueuePhrase(phrase)
                    }
                    if (buffer.length >= 220) {
                        val phrase = buffer.toString().trim()
                        buffer.clear()
                        enqueuePhrase(phrase)
                    }
                }
                if (!stopped) enqueuePhrase(buffer.toString().trim())
            } finally {
                phraseQueue.close()
            }
        }
        try {
            for (pending in phraseQueue) {
                val waitStartedAt = System.nanoTime()
                val phrase = pending.await()
                val waitMs = (System.nanoTime() - waitStartedAt) / 1_000_000
                if (!stopped) {
                    outputSampleRate = phrase.sampleRate
                    val pcm = addInterPhraseSilence(phrase)
                    val track = audioTrack ?: createTrack(phrase.sampleRate).also {
                        audioTrack = it
                        it.play()
                    }
                    val playbackHeadBefore = track.playbackHeadPosition
                    val queuedBefore = (framesWritten - playbackHeadBefore).coerceAtLeast(0)
                    log(
                        "audio_phrase_ready index=${phrase.index} awaitMs=$waitMs " +
                            "pcmFrames=${phrase.pcm.size} paddingFrames=${pcm.size - phrase.pcm.size} " +
                            "queuedBeforeFrames=$queuedBefore underruns=${track.underrunCount}"
                    )
                    val writeStartedAt = System.nanoTime()
                    check(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) >= 0) {
                        "AudioTrack rejected Kokoro PCM output."
                    }
                    framesWritten += pcm.size
                    val queuedAfter = (framesWritten - track.playbackHeadPosition).coerceAtLeast(0)
                    log(
                        "audio_phrase_written index=${phrase.index} writeMs=${(System.nanoTime() - writeStartedAt) / 1_000_000} " +
                            "queuedAfterFrames=$queuedAfter underruns=${track.underrunCount}"
                    )
                }
            }
            producer.join()
        } finally {
            producer.cancelAndJoin()
            if (!stopped && framesWritten > 0 && outputSampleRate > 0) {
                drainAudioTrack(framesWritten, outputSampleRate)
            }
            audioTrack?.stopSafely()
            audioTrack = null
            preloadedEngine?.release()
            preloadedEngine = null
            log("tts_session_finished stopped=$stopped")
        }
    }

    override fun stopSpeaking() {
        stopped = true
        audioTrack?.stopSafely()
    }

    fun release() {
        stopSpeaking()
    }

    private fun synthesize(index: Int, phrase: String): SynthesizedPhrase {
        if (stopped) return SynthesizedPhrase(index, 0, ShortArray(0))
        val generationStartedAt = System.nanoTime()
        log("tts_generation_started index=$index chars=${phrase.length} preview=${phrase.take(80)}")
        check(File(modelDirectory, "model.onnx").isFile) { "Kokoro model.onnx is missing." }
        check(File(modelDirectory, "voices.bin").isFile) { "Kokoro voices.bin is missing." }
        check(File(modelDirectory, "tokens.txt").isFile) { "Kokoro tokens.txt is missing." }
        // Sherpa-ONNX has had Android crashes when one native OfflineTts
        // pointer is reused for multiple generations. Generate one phrase
        // with one native instance, then release it before the next phrase.
        val engine = synchronized(this) {
            preloadedEngine?.also { preloadedEngine = null }
        } ?: OfflineTts(config = config())
        try {
            val generated = engine.generateWithConfig(
                phrase,
                GenerationConfig(silenceScale = 0.2f, sid = speakerId)
            )
            if (stopped) return SynthesizedPhrase(index, 0, ShortArray(0))
            val pcm = ShortArray(generated.samples.size) { index ->
                (generated.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            val generationMs = (System.nanoTime() - generationStartedAt) / 1_000_000
            val audioMs = pcm.size * 1_000L / generated.sampleRate
            log(
                "tts_generation_finished index=$index samples=${pcm.size} sampleRate=${generated.sampleRate} " +
                    "generationMs=$generationMs audioDurationMs=$audioMs " +
                    "realtimeFactor=${if (audioMs > 0) generationMs.toDouble() / audioMs else -1.0} " +
                    "underruns=${audioTrack?.underrunCount ?: -1}"
            )
            return SynthesizedPhrase(index, generated.sampleRate, pcm)
        } finally {
            engine.release()
        }
    }

    private fun drainAudioTrack(framesWritten: Int, sampleRate: Int) {
        val track = audioTrack ?: return
        val deadline = System.currentTimeMillis() +
            (framesWritten * 1_000L / sampleRate).coerceAtLeast(1_000L) + 2_000L
        log("audio_track_drain_started frames=$framesWritten sampleRate=$sampleRate state=${track.state} underruns=${track.underrunCount}")
        while (!stopped && System.currentTimeMillis() < deadline) {
            if (track.playbackHeadPosition.toLong() >= framesWritten.toLong()) break
            Thread.sleep(20L)
        }
        log("audio_track_drain_finished playbackHead=${track.playbackHeadPosition} stopped=$stopped underruns=${track.underrunCount}")
    }

    private fun addInterPhraseSilence(phrase: SynthesizedPhrase): ShortArray {
        if (phrase.index == 0 || phrase.pcm.isEmpty()) return phrase.pcm
        val paddingMs = 120L
        val paddingFrames = (phrase.sampleRate * paddingMs / 1_000L).toInt()
        log("audio_phrase_padding index=${phrase.index} paddingMs=$paddingMs")
        return ShortArray(paddingFrames + phrase.pcm.size).also { padded ->
            phrase.pcm.copyInto(padded, destinationOffset = paddingFrames)
        }
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
        // The minimum device buffer is only about 200 ms on this phone. That
        // is too little headroom while a long phrase is being written and the
        // next phrase is still being synthesized. Two seconds is still tiny
        // in memory for mono PCM (~96 KB at 24 kHz) but prevents transition
        // underruns from turning into clipped or garbled speech.
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

    private fun sentenceBoundary(buffer: StringBuilder): Int {
        for (index in buffer.indices) {
            if (buffer[index] in ".!?\n" && (index + 1 == buffer.length || buffer[index + 1].isWhitespace())) {
                return index + 1
            }
        }
        return -1
    }

    private fun AudioTrack.stopSafely() {
        runCatching { pause() }
        runCatching { flush() }
        runCatching { release() }
    }
}
