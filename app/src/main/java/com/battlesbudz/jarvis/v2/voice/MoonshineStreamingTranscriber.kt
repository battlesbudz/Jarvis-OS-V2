package com.battlesbudz.jarvis.v2.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import java.io.File

/** Owns one utterance. Native calls are serialized by AudioTurnCapture's collector. */
class MoonshineStreamingTranscriber(private val directory: File) : StreamingTranscriber {
    private val lines = linkedMapOf<Long, String>()
    private var transcriber = Transcriber(listOf(
        TranscriberOption("transcription_interval", "0.5"),
        TranscriberOption("identify_speakers", "false"),
        TranscriberOption("return_audio_data", "false")
    ))
    private var lowByte: Int? = null
    private var closed = false
    private var finished = false

    init {
        try {
            transcriber.addListener { event ->
                val line = when (event) {
                    is TranscriptEvent.LineStarted -> event.line
                    is TranscriptEvent.LineUpdated -> event.line
                    is TranscriptEvent.LineTextChanged -> event.line
                    is TranscriptEvent.LineCompleted -> event.line
                    is TranscriptEvent.Error -> throw event.cause
                    else -> null
                }
                // A native stream can finish multiple lines before Jarvis's turn endpoint.
                // Replace by ID, preserving earlier lines and avoiding duplicate event text.
                if (line != null) lines[line.id] = line.text.orEmpty()
            }
            transcriber.loadFromFiles(directory.path, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            transcriber.setUpdateInterval(0.5)
            transcriber.start()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun accept(pcm: ByteArray): String {
        check(!closed && !finished)
        val samples = FloatArray((pcm.size + if (lowByte != null) 1 else 0) / 2)
        var count = 0
        for (byte in pcm) {
            val low = lowByte
            if (low == null) lowByte = byte.toInt() and 255
            else {
                samples[count++] = (low or ((byte.toInt() and 255) shl 8)).toShort() / 32768f
                lowByte = null
            }
        }
        if (samples.isNotEmpty()) transcriber.addAudio(samples, 16_000)
        return text()
    }

    override fun finish(): String {
        check(!closed)
        if (!finished) {
            transcriber.stop() // Forced final update includes the last, incomplete native line.
            finished = true
        }
        return text()
    }

    private fun text() = lines.values.filter { it.isNotBlank() }.joinToString(" ").trim()

    override fun recover(pcm: ByteArray): String {
        check(!closed && finished)
        val samples = FloatArray(pcm.size / 2) { index ->
            val offset = index * 2
            ((pcm[offset].toInt() and 255) or ((pcm[offset + 1].toInt() and 255) shl 8)).toShort() / 32768f
        }
        if (samples.isEmpty()) return ""
        // The SDK batch API otherwise repeats the live stream's smoothed VAD gate.
        // Jarvis already confirmed speech before calling recover(). Decode that
        // bounded candidate without a second speech gate. Release the live model
        // first so recovery does not keep two native ASR models resident.
        transcriber.removeAllListeners()
        transcriber.close()
        transcriber = Transcriber(listOf(
            TranscriberOption("vad_threshold", "0.0"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("return_audio_data", "false")
        ))
        transcriber.loadFromFiles(directory.path, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
        return transcriber.transcribeWithoutStreaming(samples, 16_000)?.lines.orEmpty()
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")
    }

    override fun close() {
        if (!closed) {
            closed = true
            transcriber.removeAllListeners()
            transcriber.close()
        }
    }
}
