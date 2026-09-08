package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import java.io.File
import kotlinx.coroutines.*

/** Local speech capture alongside generation/playback, with the ordinary mic-priority contract. */
class ReplyVoiceCapture(private val context: Context, private val log: (String) -> Unit) {
    suspend fun listen(output: SherpaKokoroVoiceOutput, asrDirectory: File,
                       onConfirmed: () -> Unit): CapturedVoiceTurn = supervisorScope {
        while (true) {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val input = AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java),
                echoCancellation = true, log = log)
            val confirmed = CompletableDeferred<Unit>()
            val gated = BargeInAudioInput(input,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                playing = { output.isPlayingAudio }, pauseProbe = output::setProbePaused,
                discardQueued = input::discardBufferedAudio,
                onConfirmed = { confirmed.complete(Unit); onConfirmed() }, log = log)
            val capture = AudioTurnCapture(gated, this,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                createTranscriber = { MoonshineStreamingTranscriber(asrDirectory) }, log = log)
            try {
                capture.start(initialSilenceTimeoutMs = null)
                log("barge_listener_ready")
                // Observe capture failures while waiting for speech, too.
                val completion = async { capture.awaitTurnCompletion() }
                kotlinx.coroutines.selects.select<Unit> {
                    confirmed.onAwait { }
                    completion.onAwait { error("Interruption capture ended without confirmed speech") }
                }
                withTimeout(30_000) { completion.await() }
                return@supervisorScope CapturedVoiceTurn(capture.finalTranscript, capture.stop())
            } catch (busy: MicrophoneBusyException) {
                log("barge_listener_yielded external_microphone=true")
            } catch (timeout: TimeoutCancellationException) {
                log("barge_correction_timeout")
                return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                log("barge_listener_failed reason=${error.message}")
                // A listener failure must not cancel a valid reply or execute an empty correction.
                capture.stop()
                awaitCancellation()
            } finally { capture.stop() }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
