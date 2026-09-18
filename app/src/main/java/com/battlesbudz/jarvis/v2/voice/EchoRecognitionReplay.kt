package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import kotlinx.coroutines.*
import java.security.MessageDigest

/** No microphone, playback, call history, tools, or ASR setting changes. Models run sequentially. */
internal object EchoRecognitionReplay {
    private enum class Path { OLD_RAW_BYPASS, CALL_INPUT_ADAPTER, RAW_NATIVE_GATE, WHISPER_CONTROL }

    suspend fun run(context: Context, originals: List<DuplexAudioEvidence.Result>, status: (String) -> Unit): List<DuplexAudioEvidence.Result> =
        withContext(Dispatchers.Default) {
            val moonshine = AsrEngine.MOONSHINE.prepare(context, status)
            val whisper = AsrEngine.WHISPER.prepare(context, status)
            originals.map { original ->
                val sections = mutableListOf<String>()
                for (path in Path.entries) {
                    ensureActive()
                    status("Replaying ${original.scenario}: ${path.name.lowercase().replace('_', ' ')}…")
                    val inputEvidence = RecognitionAudioEvidence()
                    val logs = mutableListOf<String>()
                    val log: (String) -> Unit = { if (logs.size < 160) logs += it }
                    val started = System.nanoTime()
                    var detector: SpeechDetector? = null
                    val text = try {
                        if (path == Path.CALL_INPUT_ADAPTER) detector = SileroSpeechDetector.create(context.assets)
                        val transcriber = when (path) {
                            Path.RAW_NATIVE_GATE -> AsrEngine.MOONSHINE.createDiagnostic(moonshine, log, inputEvidence)
                            Path.WHISPER_CONTROL -> AsrEngine.WHISPER.createDiagnostic(whisper, log, inputEvidence)
                            else -> AsrEngine.MOONSHINE.create(moonshine, log = log, audioEvidence = inputEvidence)
                        }
                        if (path == Path.CALL_INPUT_ADAPTER) recognizeCallAdapterClip(transcriber, original.microphone, requireNotNull(detector), log)
                        else recognizeDiagnosticClip(transcriber, original.microphone)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { log("error=${error.javaClass.simpleName}: ${error.message}"); "" }
                    finally { detector?.close() }
                    val decoded = inputEvidence.snapshot()
                    sections += "path=$path elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                        "decoderBytes=${decoded.size} decoderSha256=${sha256(decoded)} decoderTruncated=${inputEvidence.truncated}\n" +
                        "rawTranscript=$text\n" + logs.joinToString("\n")
                }
                original.copy(report = """
                    Jarvis saved-recording ASR comparison
                    replayBuild=${com.battlesbudz.jarvis.v2.BuildConfig.VERSION_NAME} replayCommit=${com.battlesbudz.jarvis.v2.BuildConfig.SOURCE_COMMIT}
                    scenario=${original.scenario} sourcePcmSha256=${sha256(original.microphone)} sourceBytes=${original.microphone.size}
                    scope=offline_adapter_comparison microphoneOpened=false audioPlayed=false liveScheduling=false liveEndpointing=false
                    callAdapter=Silero_512_three_frames_CaptureSpeechGate_ExternalSpeechGate noLiveBacklog=true noRecovery=true
                    rawTranscriptsAreEvidenceOnly=true soundOnlyAndSilenceOutputsMustNotTriggerActions=true
                """.trimIndent() + "\n\n" + sections.joinToString("\n\n") + "\n\n--- Original evidence ---\n" + original.report)
            }
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
