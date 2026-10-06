package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.diagnostics.DiagnosticRecorder
import com.battlesbudz.jarvis.v2.runtime.AcceptedVoiceActionCoordinator
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Admits one turn, orders typed stages, and rearms only after its exact job and children complete. */
internal class VoiceTurnRunner(
    private val applicationScope: CoroutineScope,
    private val call: VoiceCallAccess,
    private val actions: AcceptedVoiceActionCoordinator,
    private val turnOrchestrator: TurnOrchestrator,
    private val historyAfterCutoff: () -> List<ChatEntry>,
    private val selectRequest: (CallFinalInput?) -> VoiceTurnRequest,
    private val createObservation: (VoiceTurnRequest) -> VoiceTurnObservation,
    private val createModelLease: () -> VoiceTurnModelLease,
    private val typedStage: TypedVoiceInputStage,
    private val typedInputs: VoiceTypedInputOwnership,
    private val preparation: VoiceTurnPreparation,
    private val recognition: VoiceTurnRecognition,
    private val acceptedReplies: AcceptedVoiceFollowupStage,
    private val ordinaryReplies: OrdinaryVoiceReplyStage,
    private val finalizer: VoiceTurnFinalizer,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val onTurnStarted: () -> Unit = {},
    private val onTerminalFailure: () -> Unit = {}
) {
    fun start() {
        if (!call.state.armed || call.state.turnJob?.isCompleted == false || actions.queue.hasUnfinished() ||
            (call.state.acceptedSession?.pendingReportCount() ?: 0) > 0) return
        val restored = call.state.pendingTypedHandoff.getAndSet(null)
        val next = restored ?: call.controller.currentCallId()?.let { call.state.inputQueue.claim(it) }
        if (restored != null && !call.state.inputQueue.claimExternal(restored)) return
        val queued = next?.also { typed ->
            call.state.preparingTypedInput.set(typed)
            val plan = turnOrchestrator.plan(typed.text, historyAfterCutoff().map { it.role to it.text }).actionPlan
            if (plan !is ActionTurnPlan.Ready) {
                typedStage.start(typed)
                return
            }
        }
        val request = selectRequest(queued)
        val observation = createObservation(request)
        runCatching { onTurnStarted() }
        call.state.turnJob = applicationScope.launch(Dispatchers.Default) {
            val lifetime = VoiceTurnLifetime(this, createModelLease(), call.controller.currentCallId() != null)
            var terminalFailure = false
            fun relinquishTyped() = typedInputs.relinquish(request.queuedTypedInput)
            try {
                val prepared = preparation.prepare(request, observation, lifetime)
                val finalized = when (val result = recognition.recognize(request, prepared, observation, lifetime)) {
                    is VoiceStageResult.Ready -> result.value
                    is VoiceStageResult.Finished -> { lifetime.finalMessage = result.message; return@launch }
                }
                when (val accepted = acceptedReplies.run(request, prepared, finalized, observation, lifetime)) {
                    is AcceptedVoiceStageResult.Handled -> { lifetime.finalMessage = accepted.message; return@launch }
                    AcceptedVoiceStageResult.NotApplicable -> Unit
                }
                val reply = ordinaryReplies.reply(request, prepared, finalized, observation, lifetime)
                if (reply is VoiceStageResult.Finished) lifetime.finalMessage = reply.message
            }
            catch (backlog: com.battlesbudz.jarvis.v2.voice.AudioBacklogException) {
                observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
                observation.failure = "audio_backlog"
                call.state.audioRecoveryAttempts++
                diagnosticRecorder.recordImportant("Audio buffer recovery attempt=${call.state.audioRecoveryAttempts} max=2; incomplete command discarded.")
                runCatching { call.controller.interrupt() }
                lifetime.finalMessage = if (call.state.audioRecoveryAttempts <= 2)
                    com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " audio capture recovered; say Hey Jarvis again."
                else "Voice Call turn failed: audio capture repeatedly fell behind. Restart the session."
                if (call.state.audioRecoveryAttempts > 2) terminalFailure = true
            } catch (busy: com.battlesbudz.jarvis.v2.voice.MicrophoneBusyException) {
                observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.REJECTED
                observation.failure = "microphone_busy"
                relinquishTyped()
                // Keep the same call/context. Only an unfinished user utterance is discarded.
                if (call.controller.currentCallId() != null) call.state.resumeCommandCue.set(true)
                else call.state.returnToWakeCuePending.set(true)
                diagnosticRecorder.recordImportant("Microphone yielded during capture; call=${call.controller.currentCallId()} retained=true partial_discarded=true")
                lifetime.finalMessage = "Paused — microphone interrupted; previous listening mode retained."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.CANCELLED
                observation.failure = cancelled.javaClass.simpleName
                relinquishTyped()
                if (cancelled is com.battlesbudz.jarvis.v2.voice.VoiceControlCancellation) {
                    lifetime.microphoneYielded = true // Use the same cleanup-before-rearm path.
                    diagnosticRecorder.recordImportant("Voice control requested: ${cancelled.control}")
                    lifetime.preserveCaptureOnCancellation = cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY
                    if (cancelled.control == com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) {
                        call.events.endCall()
                    }
                    lifetime.finalMessage = when (cancelled.control) {
                        com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE -> "Paused — microphone off; conversation retained."
                        com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY -> "Reply stopped — continuing Voice Call."
                        else -> com.battlesbudz.jarvis.v2.voice.VoiceCallPolicy.ENDED_PREFIX + " user control."
                    }
                } else {
                    diagnosticRecorder.recordImportant("Voice capture cancelled: ${cancelled.message ?: cancelled.javaClass.simpleName} cause=${cancelled.cause?.javaClass?.simpleName}:${cancelled.cause?.message} armed=${call.state.armed} phase=${call.state.latestStatus}")
                    if (call.state.armed && applicationScope.isActive && cancelled.message != "resuming_saved_voice_call") {
                        // An unexpected child cancellation must not leave an armed call deaf.
                        // Intentional stop disarms first; saved-call replacement owns its own restart.
                        lifetime.microphoneYielded = true
                        call.state.audioRecoveryAttempts++
                        lifetime.finalMessage = if (call.state.audioRecoveryAttempts <= 2)
                            "Recovering interrupted voice capture…"
                        else "Voice Call turn failed: capture repeatedly cancelled. Restart the session."
                        if (call.state.audioRecoveryAttempts > 2) terminalFailure = true
                        diagnosticRecorder.recordImportant("Capture cancellation recovery attempt=${call.state.audioRecoveryAttempts} max=2")
                    }
                    throw cancelled
                }
            } catch (error: Throwable) {
                terminalFailure = true
                observation.outcome = com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome.ERROR
                observation.failure = error.javaClass.simpleName
                (listOfNotNull(request.queuedTypedInput, lifetime.activePumpTypedInput.getAndSet(null))).distinctBy { it.id }.forEach { typed ->
                    if (call.state.inputQueue.terminalize(typed)) {
                        call.controller.recordTerminalInputForCall(typed.callId, typed.id,
                            "Cancelled before processing typed message: ${typed.text}")
                    }
                }
                diagnosticRecorder.record("Voice turn failed: ${error.stackTraceToString().take(4000)}")
                runCatching { call.controller.interrupt() }
                lifetime.finalMessage = "Voice Call turn failed: ${error.message ?: "unknown error"}"
            } finally {
                val cancelled = kotlin.coroutines.coroutineContext[Job]?.isActive != true
                finalizer.close(request, lifetime, cancelled)
                observation.finish(lifetime, cancelled, actions.queue::hasUnfinished)
                // Cleanup can be slow; its duration must not consume the visible error lease.
                if (terminalFailure) runCatching { onTerminalFailure() }
                if (call.state.armed && (lifetime.hadActiveCall || lifetime.wokeThisTurn) && call.controller.currentCallId() == null)
                    call.state.returnToWakeCuePending.set(true)
                if (kotlin.coroutines.coroutineContext[Job]?.isActive == true || lifetime.microphoneYielded) {
                    kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion {
                        call.events.post {
                            call.events.report(lifetime.finalMessage)
                            call.events.finished(lifetime.finalMessage)
                            if (call.state.armed && !lifetime.finalMessage.contains("turn failed", true)) call.events.restartTurn()
                            else {
                                call.state.armed = false
                                // Turn teardown with a service stop is a true session end.
                                com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.sessionAlive.value = false
                                call.events.stopService()
                            }
                        }
                    }
                }
            }
        }
    }
}
