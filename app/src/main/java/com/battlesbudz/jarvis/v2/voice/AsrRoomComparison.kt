package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream

/** One bounded recording, decoded sequentially by both engines. No LLM, tools, TTS or
 * speaker-profile training. Background-only runs are useful false-positive measurements. */
object AsrRoomComparison {
    suspend fun run(context: Context, status: (String) -> Unit): String = withContext(Dispatchers.Default) {
        val runtime = com.battlesbudz.jarvis.v2.JarvisRuntime.get(context)
        check(!runtime.voiceSessionArmed && runtime.modelStore.tryBeginModelOperation()) { "Stop the Jarvis session before comparing recognition." }
        try {
        val directories = AsrEngine.entries.associateWith { it.prepare(context, status) }
        coroutineScope {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val input = QuietSpeechAudioInput(AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java), noiseSuppression = true))
            val audio = ByteArrayOutputStream()
            try {
                input.start()
                status("Recording 8 seconds. Speak, or stay silent to test the room alone…")
                withTimeoutOrNull(8_000) {
                    input.chunks().collect { chunk ->
                        val remaining = 8 * 32000 - audio.size()
                        if (remaining > 0) audio.write(chunk, 0, minOf(remaining, chunk.size))
                    }
                }
            } finally { withContext(NonCancellable) { input.stop() } }
            val pcm = audio.toByteArray()
            check(pcm.isNotEmpty()) { "Microphone returned no audio" }
            val result = StringBuilder("Same recording: ${pcm.size / 32} ms. No commands executed.\n")
            val store = AsrComparisonStore(context.getSharedPreferences("asr_comparison", Context.MODE_PRIVATE))
            val sampleId = java.util.UUID.randomUUID().toString()
            for (engine in AsrEngine.entries) {
                ensureActive(); status("Testing ${engine.label} on the recording…")
                val loadAt = System.nanoTime()
                val recognizer = engine.create(directories.getValue(engine), live = false)
                val loadMs = (System.nanoTime() - loadAt) / 1_000_000
                try {
                    val decodeAt = System.nanoTime()
                    // Feed both the identical waveform in the same 100 ms chunks.
                    var offset = 0
                    var maxChunkMs = 0L
                    var partials = 0
                    var previous = ""
                    while (offset < pcm.size) {
                        ensureActive()
                        val end = minOf(offset + 3200, pcm.size)
                        val began = System.nanoTime()
                        val partial = recognizer.accept(pcm.copyOfRange(offset, end))
                        maxChunkMs = maxOf(maxChunkMs, (System.nanoTime() - began) / 1_000_000)
                        if (partial.isNotBlank() && partial != previous) { partials++; previous = partial }
                        offset = end
                    }
                    val finishAt = System.nanoTime()
                    val text = recognizer.finish()
                    val finishMs = (System.nanoTime() - finishAt) / 1_000_000
                    val decodeMs = (finishAt - decodeAt) / 1_000_000
                    val id = "$sampleId-${engine.id}"
                    store.add(id, AsrCaptureMetrics(loadMs, 0, pcm.size / 32L, decodeMs, maxChunkMs, null, partials, finishMs, "same_recording_test"), text, engine)
                    result.append("\n${engine.label}: ${text.ifBlank { "[no words]" }}\nDecode: ${decodeMs + finishMs} ms; load: $loadMs ms\n")
                } finally { recognizer.close() }
            }
            result.toString()
        }
        } finally { runtime.modelStore.endModelOperation() }
    }
}
