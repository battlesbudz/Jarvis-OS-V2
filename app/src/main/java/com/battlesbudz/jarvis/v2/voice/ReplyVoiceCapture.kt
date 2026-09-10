package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import java.io.File
import kotlinx.coroutines.*

/** Local speech capture alongside generation/playback, with the ordinary mic-priority contract. */
class ReplyVoiceCapture(private val context: Context, private val log: (String) -> Unit) {
    suspend fun listen(output: SherpaKokoroVoiceOutput, asrDirectory: File,
                       onConfirmed: () -> Unit, asrEngine: AsrEngine = AsrEngine.MOONSHINE, acceptCandidate: (ByteArray) -> Boolean = { true }, onPartialTranscript: (String) -> Unit = {}, trace: VoiceTurnTrace? = null,
                       inputFactory: (suspend () -> AudioInput)? = null, modelSession: VoiceModelSession? = null): CapturedVoiceTurn = recoverReplyListener(log) {
        supervisorScope {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val input = inputFactory?.invoke() ?: AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java),
                echoCancellation = true, noiseSuppression = true, log = log)
            val confirmed = CompletableDeferred<Unit>()
            val gated = KeywordBargeInAudioInput(input,
                createDetector = { MicroInterruptionKeywords(context.assets) },
                onConfirmed = { _ ->
                    trace?.mark(VoiceTurnTrace.Stage.INTERRUPTION_CONFIRMED)
                    trace?.mark(VoiceTurnTrace.Stage.PLAYBACK_STOP_REQUESTED)
                    output.stopSpeaking()
                    confirmed.complete(Unit)
                    onConfirmed()
                }, log = log)
            val capture = AudioTurnCapture(gated, this,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                createTranscriber = { LazyStreamingTranscriber { asrEngine.create(asrDirectory, log = log, modelSession = modelSession) } }, log = log,
                allowAudioOnlyTurns = true, acceptCandidate = acceptCandidate,
                onPartialTranscript = { text, _ -> onPartialTranscript(text) })
            try {
                capture.start(initialSilenceTimeoutMs = null)
                log("barge_capture_ready keywordReadiness=reported_separately naturalSpeechReady=false")
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
