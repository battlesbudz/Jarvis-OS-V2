package com.battlesbudz.jarvis.v2.voice

import ai.moonshine.voice.Transcriber
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Call-owned weights; utterance streams and decoder session IDs still belong to individual turns. */
class VoiceModelSession(log: (String) -> Unit = {}) {
    internal val tts = CallModelSlot<OfflineTts>({ it.release() }, { log("tts_$it") })
    internal val moonshine = CallModelSlot<Transcriber>({ it.removeAllListeners(); it.close() }, { log("moonshine_$it") })
    internal val whisper = CallModelSlot<OfflineRecognizer>({ it.release() }, { log("whisper_$it") })
    internal val ttsDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jarvis-call-tts").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    /** Invoke after the call's capture and output borrowers have joined. */
    fun close() {
        try { tts.close() } finally {
            try { moonshine.close() } finally {
                try { whisper.close() } finally { ttsDispatcher.close() }
            }
        }
    }
}
