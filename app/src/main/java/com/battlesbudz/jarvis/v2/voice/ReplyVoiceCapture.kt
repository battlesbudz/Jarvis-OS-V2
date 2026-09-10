package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.media.AudioManager
import java.io.File
import kotlinx.coroutines.*

/** Local speech capture alongside generation/playback, with the ordinary mic-priority contract. */
class ReplyVoiceCapture(private val context: Context, private val log: (String) -> Unit) {
    suspend fun listen(output: SherpaKokoroVoiceOutput, asrDirectory: File,
                       onConfirmed: () -> Unit, onPartialTranscript: (String) -> Unit = {},
                       asrEngine: AsrEngine = AsrEngine.MOONSHINE,
                       acceptCandidate: (ByteArray) -> Boolean = { true },
                       trace: VoiceTurnTrace? = null,
                       inputFactory: (suspend () -> AudioInput)? = null,
                       modelSession: VoiceModelSession? = null): CapturedVoiceTurn = recoverReplyListener(log) {
        supervisorScope {
            MicrophoneInterruptionMonitor.awaitAvailable()
            val input = inputFactory?.invoke() ?: AndroidAudioInput(this,
                audioManager = context.getSystemService(AudioManager::class.java),
                echoCancellation = true, noiseSuppression = true, log = log)
            val confirmed = CompletableDeferred<Unit>()
            // Keep both interruption paths live on the same capture: micro-wake words
            // provide an immediate explicit stop, while Moonshine recognizes ordinary
            // speech. The previous keyword-only wrapper made natural barge-in impossible.
            val gated = BargeInAudioInput(input,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                playing = { output.isPlayingAudio },
                createTranscriber = { PacedStreamingTranscriber(
                    asrEngine.create(asrDirectory, log = log, modelSession = modelSession), log) },
                spokenText = output::recentSpokenText,
                onConfirmed = {
                    output.stopSpeaking()
                    confirmed.complete(Unit)
                    onConfirmed()
                },
                log = log,
                createKeywordDetector = { MicroInterruptionKeywords(context.assets) })
            val capture = AudioTurnCapture(gated, this,
                createDetector = { SileroSpeechDetector.create(context.assets) },
                createTranscriber = { LazyStreamingTranscriber { asrEngine.create(asrDirectory, log = log, modelSession = modelSession) } }, log = log,
                allowAudioOnlyTurns = true,
                acceptCandidate = acceptCandidate,
                onAcceptedCandidate = { trace?.mark(VoiceTurnTrace.Stage.INTERRUPTION_CONFIRMED) },
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
