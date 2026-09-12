package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Optional background window decoding: Whisper base is not a native streaming recognizer. Decoding never runs on the live microphone thread. VAD bounds the retained input and Gemma still handles blanks. */
class WhisperTranscriber(private val directory: File, live: Boolean = true, log: (String) -> Unit = {}, modelSession: VoiceModelSession? = null) : StreamingTranscriber {
    private val audio = RollingAudioBuffer(AudioFormat(16_000), maxDurationMs = 25_000)
    private fun createRecognizer() = OfflineRecognizer(config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(
            encoder = File(directory, "base.en-encoder.int8.onnx").path,
            decoder = File(directory, "base.en-decoder.int8.onnx").path),
            tokens = File(directory, "base.en-tokens.txt").path, modelType = "whisper", numThreads = 2)))
    private val lease = modelSession?.whisper?.acquire(directory.path, create = ::createRecognizer)
    private val recognizer = lease?.value ?: createRecognizer()
    private var healthy = true
    private fun releaseRecognizer() { if (lease != null) lease.finish(healthy) else recognizer.release() }
    private var closed = false
    private val streaming = if (live) AsyncWhisperSession(::decode, ::releaseRecognizer, log) else null
    override val noTextSilenceMs: Long get() = 900
    override fun observeSpeech(speech: Boolean) { streaming?.observeSpeech(speech) }
    override fun accept(pcm: ByteArray): String { check(!closed); if (streaming != null) return streaming.accept(pcm); audio.append(pcm); return "" }
    override fun finish(): String = streaming?.finish() ?: decode(audio.snapshot())
    override fun recover(pcm: ByteArray): String = streaming?.recover(pcm) ?: decode(pcm)
    private fun decode(pcm: ByteArray): String {
        check(!closed)
        if (pcm.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(pcmFloats(pcm), 16_000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } catch (error: Throwable) { healthy = false; throw error }
        finally { stream.release() }
    }
    override fun close() { if (!closed) { if (streaming != null) streaming.close() else releaseRecognizer(); closed = true; audio.clear() } }
}

internal fun pcmFloats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
    ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
}
