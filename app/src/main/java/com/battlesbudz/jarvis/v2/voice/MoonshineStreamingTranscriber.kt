package com.battlesbudz.jarvis.v2.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import java.io.File

/** Owns one utterance. Native calls are serialized by AudioTurnCapture's collector. */
class MoonshineStreamingTranscriber(private val directory: File, private val updateIntervalSeconds: Double = DEFAULT_INTERVAL, modelSession: VoiceModelSession? = null, reserveReplyProbes: Boolean = true) : StreamingTranscriber {
    private val lines = linkedMapOf<Long, String>()
    private fun createLoaded(): Transcriber {
        val created = Transcriber(listOf(
            TranscriberOption("transcription_interval", updateIntervalSeconds.toString()),
            TranscriberOption("vad_threshold", "0.3"),
            TranscriberOption("identify_speakers", "false"),
            TranscriberOption("return_audio_data", "false")
        ))
        try {
            created.loadFromFiles(directory.path, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            created.setUpdateInterval(updateIntervalSeconds)
            return created
        } catch (error: Throwable) { created.close(); throw error }
    }
    // SDK 0.1.5 retains a private completed-line map. Rotate after eight streams
    // to bound that bookkeeping. Commands rotate early when necessary to reserve
    // all bounded reply probes; optional probes themselves never rotate or load.
    private val lease = modelSession?.moonshine?.acquire("${directory.path}:$updateIntervalSeconds", MAX_STREAMS,
        requiredUses = if (reserveReplyProbes) 1 + NaturalBargeInAudioInput.MAX_PROBES else 1, create = ::createLoaded)
    private var leased = lease != null
    private var transcriber = lease?.value ?: createLoaded()
    private var streamHandle = -1
    private var healthy = true
    private inline fun <T> native(block: () -> T): T = try { block() }
        catch (error: Throwable) { healthy = false; throw error }
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
            streamHandle = transcriber.createStream()
            check(streamHandle >= 0) { "Moonshine could not create an utterance stream." }
            transcriber.startStream(streamHandle)
        } catch (error: Throwable) {
            healthy = false
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
        if (samples.isNotEmpty()) native { transcriber.addAudioToStream(streamHandle, samples, 16_000) }
        return text()
    }

    override fun finish(): String {
        check(!closed)
        if (!finished) {
            native { transcriber.stopStream(streamHandle) } // Forced final update includes the last, incomplete native line.
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
        transcriber.freeStream(streamHandle); streamHandle = -1
        if (leased) { lease!!.finish(healthy = false); leased = false } else transcriber.close()
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
            try {
                transcriber.removeAllListeners()
                if (streamHandle >= 0) native { transcriber.freeStream(streamHandle) }
            } finally {
                streamHandle = -1
                if (leased) { lease!!.finish(healthy); leased = false } else transcriber.close()
            }
        }
    }
    companion object {
        private const val DEFAULT_INTERVAL = 0.25
        private const val MAX_STREAMS = 8
        fun canReuseForProbe(directory: File, session: VoiceModelSession?): Boolean =
            session?.moonshine?.canReuse("${directory.path}:$DEFAULT_INTERVAL", MAX_STREAMS) == true
    }
}
