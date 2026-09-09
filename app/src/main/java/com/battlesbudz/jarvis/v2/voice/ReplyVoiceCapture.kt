package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import java.io.File
import kotlinx.coroutines.*

/** Local speech capture alongside generation/playback, with the ordinary mic-priority contract. */
class ReplyVoiceCapture(private val context: Context, private val log: (String) -> Unit) {
    suspend fun listen(output: SherpaKokoroVoiceOutput, asrDirectory: File,
                       onConfirmed: () -> Unit, onPartialTranscript: (String) -> Unit = {}): CapturedVoiceTurn = recoverReplyListener(log) {
        supervisorScope {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val input = AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java),
                echoCancellation = true, noiseSuppression = true, log = log)
            val confirmed = CompletableDeferred<Unit>()
            val gated = KeywordBargeInAudioInput(input,
                createDetector = { MicroInterruptionKeywords(context.assets) },
                onConfirmed = { _ ->
                    output.stopSpeaking()
                    confirmed.complete(Unit)
                    onConfirmed()
                }, log = log)
            val capture = AudioTurnCapture(gated, this,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                createTranscriber = { LazyStreamingTranscriber { MoonshineStreamingTranscriber(asrDirectory) } }, log = log,
                allowAudioOnlyTurns = true,
                onPartialTranscript = { text, _ -> onPartialTranscript(text) })
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
                throw busy
            } catch (timeout: TimeoutCancellationException) {
                log("barge_correction_timeout")
                return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (confirmed.isCompleted) {
                    // Never replay an incomplete correction after a capture failure.
                    return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                }
                throw error
            } finally { capture.stop() }
        }
    }
}
