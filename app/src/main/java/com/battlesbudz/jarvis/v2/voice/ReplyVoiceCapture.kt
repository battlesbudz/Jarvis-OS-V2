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
            var naturalReference: String? = null
            var stopOnly = false
            fun confirm() {
                trace?.mark(VoiceTurnTrace.Stage.INTERRUPTION_CONFIRMED)
                trace?.mark(VoiceTurnTrace.Stage.PLAYBACK_STOP_REQUESTED)
                output.stopSpeaking()
                confirmed.complete(Unit)
                onConfirmed()
            }
            val gated: AudioInput = if (asrEngine == AsrEngine.MOONSHINE) {
                NaturalBargeInAudioInput(input,
                    createKeyword = { MicroInterruptionKeywords(context.assets) },
                    createVad = { SileroSpeechDetector.create(context.assets) },
                    createTranscriber = {
                        check(MoonshineStreamingTranscriber.canReuseForProbe(asrDirectory, modelSession)) { "probe_model_not_warm" }
                        MoonshineStreamingTranscriber(asrDirectory, modelSession = modelSession, reserveReplyProbes = false)
                    },
                    playing = { output.isPlayingAudio }, reference = { output.recentSpokenText() },
                    hasPlaybackBudget = output::hasInterruptionBudget,
                    canContinuePlayback = output::canContinueInterruption,
                    onConfirmed = { natural, evidence ->
                        if (natural) naturalReference = evidence
                        else stopOnly = evidence == "stop"
                        confirm()
                    }, log = log)
            } else KeywordBargeInAudioInput(input,
                createDetector = { MicroInterruptionKeywords(context.assets) },
                onConfirmed = { keyword -> stopOnly = keyword == "stop"; confirm() },
                allowKeyword = { keyword -> keyword != "stop" || !output.isPlayingAudio }, log = log)
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
                if (stopOnly) {
                    // Stop is a control, not a request for an audio-model answer. The retained
                    // microphone hands subsequent speech to the ordinary follow-up listener.
                    log("barge_stop_complete destination=followup_listening audio_fallback=false")
                    completion.cancel()
                    return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                }
                withTimeout(130_000) { completion.await() }
                val wav = capture.stop()
                val finalText = capture.finalTranscript
                if (capture.recognitionIssue != null) {
                    return@supervisorScope CapturedVoiceTurn(finalText, wav, capture.audioIsComplete, capture.recognitionIssue)
                }
                val echo = naturalReference
                if (echo != null) {
                    // Recheck the final recognition: provisional words never authorize actions.
                    val checked = NaturalCorrectionText.resolve(finalText, echo)
                    if (checked == null) {
                        log("barge_correction_discarded reason=final_request_not_confirmed")
                        return@supervisorScope CapturedVoiceTurn("", byteArrayOf())
                    }
                    return@supervisorScope CapturedVoiceTurn(checked, wav, capture.audioIsComplete, capture.recognitionIssue)
                }
                return@supervisorScope CapturedVoiceTurn(finalText, wav, capture.audioIsComplete, capture.recognitionIssue)
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
