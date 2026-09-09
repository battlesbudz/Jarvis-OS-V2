package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Endpoint decoding: Whisper base is not a native streaming recognizer. No repeated full-window
 * decoding on the microphone thread. VAD bounds the retained input and Gemma still handles blanks. */
class WhisperTranscriber(directory: File) : StreamingTranscriber {
    private val audio = RollingAudioBuffer(AudioFormat(16_000), maxDurationMs = 25_000)
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(
            encoder = File(directory, "base.en-encoder.int8.onnx").path,
            decoder = File(directory, "base.en-decoder.int8.onnx").path),
            tokens = File(directory, "base.en-tokens.txt").path, modelType = "whisper", numThreads = 2)))
    private var closed = false
    override fun accept(pcm: ByteArray): String { check(!closed); audio.append(pcm); return "" }
    override fun finish(): String = decode(audio.snapshot())
    override fun recover(pcm: ByteArray): String = decode(pcm)
    private fun decode(pcm: ByteArray): String {
        check(!closed)
        if (pcm.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(pcmFloats(pcm), 16_000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() { if (!closed) { closed = true; audio.clear(); recognizer.release() } }
}

internal fun pcmFloats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
    ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
}
