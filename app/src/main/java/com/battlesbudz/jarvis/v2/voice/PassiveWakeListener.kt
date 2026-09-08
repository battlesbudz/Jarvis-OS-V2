package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** Only keyword scores are computed here. No ASR, transcript, WAV, or Gemma input exists yet. */
class PassiveWakeListener(directory: File, private val log: (String) -> Unit = {}) : AutoCloseable {
    private val keywordFile = File(directory, "hey-jarvis.txt").apply {
        writeText("▁HE Y ▁JA R VI S @HEY_JARVIS\n")
    }
    private val spotter = KeywordSpotter(config = KeywordSpotterConfig(
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(directory, "encoder-${WakeWordModelStore.SUFFIX}").path,
                decoder = File(directory, "decoder-${WakeWordModelStore.SUFFIX}").path,
                joiner = File(directory, "joiner-${WakeWordModelStore.SUFFIX}").path),
            tokens = File(directory, "tokens.txt").path,
            numThreads = 1, modelType = "zipformer2"),
        keywordsFile = keywordFile.path, keywordsScore = 1.5f, keywordsThreshold = 0.35f
    ))
    // Encoded with this model's own bpe.model, never guessed letter spellings.
    private val stream = spotter.createStream()
    suspend fun awaitWake(input: AudioInput) {
        var frames = 0L
        var lastReport = 0L
        log("wake_capture_started sampleRate=${input.sampleRateHz} threshold=0.35")
        WakeWordGate.await(input) { pcm ->
            val samples = FloatArray(pcm.size / 2) { i ->
                (((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toFloat() / 32768f)
            }
            frames += samples.size
            if (frames - lastReport >= input.sampleRateHz * 3L) {
                lastReport = frames
                val rms = kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
                log("wake_capture_level elapsedMs=${frames * 1000 / input.sampleRateHz} rmsPcm16=${(rms * 32768).toInt()}")
            }
            stream.acceptWaveform(samples, input.sampleRateHz)
            var found = false
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotBlank()) { found = true; break }
            }
            if (found) log("wake_keyword_matched keyword=HEY_JARVIS")
            found
        }
    }
    override fun close() { stream.release(); spotter.release() }
}
