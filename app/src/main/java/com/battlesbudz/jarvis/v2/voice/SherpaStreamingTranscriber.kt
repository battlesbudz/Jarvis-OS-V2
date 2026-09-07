package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class SherpaStreamingTranscriber(directory: File) : StreamingTranscriber {
    private val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(directory, AsrModelStore.ENCODER).path,
                decoder = File(directory, AsrModelStore.DECODER).path,
                joiner = File(directory, AsrModelStore.JOINER).path
            ),
            tokens = File(directory, "tokens.txt").path,
            numThreads = 2, provider = "cpu", modelType = "zipformer"
        ),
        enableEndpoint = false, decodingMethod = "greedy_search"
    ))
    private val stream = try { recognizer.createStream() } catch (error: Throwable) {
        recognizer.release()
        throw error
    }
    private var closed = false
    private var finished = false
    private var lowByte: Int? = null

    override fun accept(pcm: ByteArray): String {
        check(!closed && !finished)
        val samples = FloatArray((pcm.size + if (lowByte != null) 1 else 0) / 2)
        var count = 0
        for (byte in pcm) {
            val low = lowByte
            if (low == null) lowByte = byte.toInt() and 0xff
            else {
                val word = low or ((byte.toInt() and 0xff) shl 8)
                samples[count++] = word.toShort().toInt() / 32768f
                lowByte = null
            }
        }
        if (samples.isNotEmpty()) stream.acceptWaveform(samples, 16_000)
        return decode()
    }

    override fun finish(): String {
        check(!closed)
        if (!finished) {
            // Drain the streaming encoder's right context without recording more audio.
            stream.acceptWaveform(FloatArray(12_800), 16_000)
            stream.inputFinished()
            finished = true
        }
        return decode()
    }

    private fun decode(): String {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { stream.release() } finally { recognizer.release() }
        }
    }
}
