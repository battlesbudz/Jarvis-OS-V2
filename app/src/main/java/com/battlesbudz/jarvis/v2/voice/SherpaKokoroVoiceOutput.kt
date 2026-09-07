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

/** Local Jarvis voice output: Gemma text -> Kokoro PCM -> Android audio route. */
class SherpaKokoroVoiceOutput(
    private val modelDirectory: String,
    private val speakerId: Int = 10,
    private val numThreads: Int = 2
) : VoiceOutput {
    @Volatile private var stopped = false
    private var audioTrack: AudioTrack? = null
    private var tts: OfflineTts? = null

    override suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit) {
        stopped = false
        val engine = tts ?: OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$modelDirectory/model.onnx",
                        voices = "$modelDirectory/voices.bin",
                        tokens = "$modelDirectory/tokens.txt",
                        dataDir = "$modelDirectory/espeak-ng-data"
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
        ).also { tts = it }

        val sampleRate = engine.sampleRate()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 5)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
            .build()
        audioTrack = track
        track.play()

        val buffer = StringBuilder()
        try {
            chunks.collect { token ->
                if (stopped) return@collect
                buffer.append(token)
                while (true) {
                    val boundary = sentenceBoundary(buffer)
                    if (boundary <= 0) break
                    val phrase = buffer.substring(0, boundary).trim()
                    buffer.delete(0, boundary)
                    if (phrase.isNotBlank()) synthesize(engine, track, phrase, onChunkStarted)
                }
                if (buffer.length >= 220) {
                    val phrase = buffer.toString().trim()
                    buffer.clear()
                    if (phrase.isNotBlank()) synthesize(engine, track, phrase, onChunkStarted)
                }
            }
            if (!stopped) {
                val phrase = buffer.toString().trim()
                if (phrase.isNotBlank()) synthesize(engine, track, phrase, onChunkStarted)
            }
        } finally {
            track.stopSafely()
            audioTrack = null
        }
    }

    override fun stopSpeaking() {
        stopped = true
        audioTrack?.stopSafely()
    }

    fun release() {
        stopSpeaking()
        tts?.release()
        tts = null
    }

    private fun synthesize(
        engine: OfflineTts,
        track: AudioTrack,
        phrase: String,
        onChunkStarted: (String) -> Unit
    ) {
        if (stopped) return
        onChunkStarted(phrase)
        engine.generateWithConfigAndCallback(
            phrase,
            GenerationConfig(silenceScale = 0.2f, sid = speakerId)
        ) { samples ->
            if (stopped) return@generateWithConfigAndCallback 0
            val pcm = ShortArray(samples.size) { index ->
                (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            1
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
