package com.battlesbudz.jarvis.v2.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Local Jarvis voice output: Gemma text -> Kokoro PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val speakerId: Int = 10,
    private val numThreads: Int = 2,
    private val log: (String) -> Unit = {}
) : VoiceOutput {
    @Volatile private var stopped = false
    private var audioTrack: AudioTrack? = null

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) {
        stopped = false
        log("tts_session_started modelDir=$modelDirectory speaker=$speakerId threads=$numThreads")
        val buffer = StringBuilder()
        var framesWritten = 0
        var outputSampleRate = 0
        try {
            chunks.collect { token ->
                if (stopped) return@collect
                buffer.append(token)
                while (true) {
                    val boundary = sentenceBoundary(buffer)
                    if (boundary <= 0) break
                    val phrase = buffer.substring(0, boundary).trim()
                    buffer.delete(0, boundary)
                    if (phrase.isNotBlank()) {
                        val result = synthesize(phrase, onChunkStarted)
                        outputSampleRate = result.first
                        framesWritten += result.second
                    }
                }
                if (buffer.length >= 220) {
                    val phrase = buffer.toString().trim()
                    buffer.clear()
                    if (phrase.isNotBlank()) {
                        val result = synthesize(phrase, onChunkStarted)
                        outputSampleRate = result.first
                        framesWritten += result.second
                    }
                }
            }
            if (!stopped) {
                val phrase = buffer.toString().trim()
                if (phrase.isNotBlank()) {
                    val result = synthesize(phrase, onChunkStarted)
                    outputSampleRate = result.first
                    framesWritten += result.second
                }
            }
        } finally {
            if (!stopped && framesWritten > 0 && outputSampleRate > 0) {
                drainAudioTrack(framesWritten, outputSampleRate)
            }
            audioTrack?.stopSafely()
            audioTrack = null
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

    private fun synthesize(
        phrase: String,
        onChunkStarted: (String) -> Unit
    ): Pair<Int, Int> {
        if (stopped) return 0 to 0
        log("tts_generation_started chars=${phrase.length} preview=${phrase.take(80)}")
        onChunkStarted(phrase)
        check(File(modelDirectory, "model.onnx").isFile) { "Kokoro model.onnx is missing." }
        check(File(modelDirectory, "voices.bin").isFile) { "Kokoro voices.bin is missing." }
        check(File(modelDirectory, "tokens.txt").isFile) { "Kokoro tokens.txt is missing." }
        // Sherpa-ONNX has had Android crashes when one native OfflineTts
        // pointer is reused for multiple generations. Generate one phrase
        // with one native instance, then release it before the next phrase.
        val engine = OfflineTts(config = config())
        try {
            val generated = engine.generateWithConfig(
                phrase,
                GenerationConfig(silenceScale = 0.2f, sid = speakerId)
            )
            if (stopped) return 0 to 0
            val track = audioTrack ?: createTrack(generated.sampleRate).also {
                audioTrack = it
                it.play()
            }
            val pcm = ShortArray(generated.samples.size) { index ->
                (generated.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            check(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) >= 0) {
                "AudioTrack rejected Kokoro PCM output."
            }
            log("tts_generation_finished samples=${pcm.size} sampleRate=${generated.sampleRate}")
            return generated.sampleRate to pcm.size
        } finally {
            engine.release()
        }
    }

    private fun drainAudioTrack(framesWritten: Int, sampleRate: Int) {
        val track = audioTrack ?: return
        val deadline = System.currentTimeMillis() +
            (framesWritten * 1_000L / sampleRate).coerceAtLeast(1_000L) + 2_000L
        log("audio_track_drain_started frames=$framesWritten sampleRate=$sampleRate state=${track.state}")
        while (!stopped && System.currentTimeMillis() < deadline) {
            if (track.playbackHeadPosition.toLong() >= framesWritten.toLong()) break
            Thread.sleep(20L)
        }
        log("audio_track_drain_finished playbackHead=${track.playbackHeadPosition} stopped=$stopped")
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
        ).coerceAtLeast(sampleRate / 5)
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
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "AudioTrack could not initialize for Kokoro output."
                }
                track.setVolume(1.0f)
                log("audio_track_ready state=${track.state} sampleRate=$sampleRate buffer=$minBuffer")
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
