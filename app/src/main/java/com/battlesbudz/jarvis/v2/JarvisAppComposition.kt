package com.battlesbudz.jarvis.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.ui.CallEvidenceActions
import com.battlesbudz.jarvis.v2.ui.CallEvidenceSnapshot
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff

/** Compatibility composition boundary for the established JarvisApp entrypoint/test ABI. */
@Composable
internal fun WithPipelineDiagnostics(
    content: @Composable (AndroidPipelineBenchmarkStore, CallEvidenceActions) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { JarvisRuntime.get(context) }
    val callEvidence = remember(runtime) {
        CallEvidenceActions(
            armAudio = LiveCallAudioEvidence::arm,
            clearAudio = LiveCallAudioEvidence::clear,
            snapshot = {
                val audio = LiveCallAudioEvidence.snapshot()
                CallEvidenceSnapshot(
                    report = "Jarvis live call echo test\n" +
                        "scope=retained_call_diagnostics microphoneRecording=${audio != null} acousticCancellation=not_measured\n" +
                        "Save one ZIP after each call test, before starting the next call.\n\n" +
                        runtime.diagnosticRecorder.snapshot() + "\n\n" + MicrophoneHandoff.diagnostics(),
                    audio = audio,
                )
            },
        )
    }
    content(runtime.pipelineBenchmarkStore, callEvidence)
}
