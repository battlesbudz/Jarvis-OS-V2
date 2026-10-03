package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.*

@Composable
fun AudioPathDiagnosticCard(enabled: Boolean, onBusyChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf<ByteArray?>(null) }
    var recognition by remember { mutableStateOf<AudioPathDiagnostic.RecognitionResult?>(null) }
    var report by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val busy = job?.isActive == true
    var recognitionRequested by remember { mutableStateOf(false) }
    fun record() {
        if (!enabled || job?.isActive == true) return
        job = scope.launch {
            onBusyChanged(true)
            recording = null
            recognition = null
            report = ""
            status = "Preparing microphone…"
            try {
                if (recognitionRequested) {
                    val engine = AsrEngine.selected(context)
                    val result = withContext(Dispatchers.Default) {
                        val directory = engine.prepare(context) { message -> scope.launch { status = message } }
                        val microphoneEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
                        val recognized = AudioPathDiagnostic.recognize(
                            AndroidAudioInput(this, audioManager = context.getSystemService(AudioManager::class.java),
                                echoCancellation = true, noiseSuppression = true,
                                log = { message -> synchronized(microphoneEvents) { if (microphoneEvents.size < 40) microphoneEvents.add(message) } }),
                            createDetector = { SileroSpeechDetector.create(context.assets) },
                            createTranscriber = { evidence -> engine.create(directory, audioEvidence = evidence) },
                            onReady = { scope.launch { status = "Listening for 8 seconds. Say a short question, then stay quiet." } })
                        recognized.copy(report = synchronized(microphoneEvents) { microphoneEvents.joinToString("\n") } + "\n" + recognized.report)
                    }
                    recognition = result
                    recording = result.microphonePcm
                    report = "Jarvis recognition audio test\nbuild=${com.battlesbudz.jarvis.v2.BuildConfig.VERSION_NAME} " +
                        "engine=${engine.id} model=${engine.modelVersion}\n${result.report}"
                    status = "Recognition test finished. Compare the two recordings and the words below."
                    return@launch
                }
                status = "Recording 8 seconds. Say ‘Open YouTube’ and ‘Hey Jarvis’."
                val bytes = withContext(Dispatchers.Default) {
                    AudioPathDiagnostic.record(AndroidAudioInput(this,
                        audioManager = context.getSystemService(AudioManager::class.java),
                        echoCancellation = true, noiseSuppression = true,
                        log = { MicrophoneHandoff.record("audio_test $it") }))
                }
                recording = bytes
                val signal = Pcm16Signal.measure(bytes)
                status = "Recorded 8 seconds. Play it back and check for missing words, noise or distortion."
                MicrophoneHandoff.record("audio_test_complete bytes=${bytes.size} rms=${signal.rms} peak=${signal.peak}")
            } catch (cancelled: CancellationException) { status = "Recording stopped."; throw cancelled }
            catch (error: Exception) { status = "Audio test failed: ${error.message}" }
            finally { onBusyChanged(false); job = null }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) record() else status = "Microphone permission is needed for the recording test."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { job?.cancel(); recording = null; recognition = null; report = "" }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (job == null) onBusyChanged(false)
            job?.cancel(); recording = null; recognition = null
            // The running job releases busy only after native/microphone cleanup finishes.
        }
    }
    Text("Check what Jarvis hears")
    Text("Uses the same microphone path as voice calls. Audio stays in memory and is discarded when you leave these settings.")
    TextButton(onClick = {
        recognitionRequested = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, enabled = enabled && !busy) { Text("Record 8 seconds") }
    TextButton(onClick = {
        recognitionRequested = true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, enabled = enabled && !busy) { Text("Record and test recognition") }
    TextButton(onClick = {
        val bytes = recording ?: return@TextButton
        job = scope.launch {
            onBusyChanged(true)
            status = "Playing your recording…"
            try { AudioPathDiagnostic.play(bytes); status = "Playback finished." }
            catch (cancelled: CancellationException) { status = "Playback stopped."; throw cancelled }
            catch (error: Exception) { status = "Playback failed: ${error.message}" }
            finally { onBusyChanged(false); job = null }
        }
    }, enabled = enabled && !busy && recording != null) { Text("Play recording") }
    recognition?.let { result ->
        Text("Recognized: ${result.transcript.ifBlank { "No words recognized." }}")
        Text("The recognition recording contains the audio actually sent to the selected recognizer. Whisper shows its latest decode window; Moonshine shows its streamed input. This test records for 8 seconds instead of using conversational end-of-turn timing.")
        TextButton(onClick = {
            job = scope.launch {
                onBusyChanged(true)
                status = "Playing recognition input…"
                try { AudioPathDiagnostic.play(result.decoderPcm); status = "Playback finished." }
                catch (cancelled: CancellationException) { status = "Playback stopped."; throw cancelled }
                catch (error: Exception) { status = "Playback failed: ${error.message}" }
                finally { onBusyChanged(false); job = null }
            }
        }, enabled = enabled && !busy && result.decoderPcm.isNotEmpty()) { Text("Play recognition input") }
        TextButton(onClick = {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Jarvis recognition test", report))
            status = "Recognition diagnostics copied."
        }, enabled = !busy) { Text("Copy recognition diagnostics") }
    }
    if (busy) TextButton(onClick = { job?.cancel() }) { Text("Stop audio test") }
    if (status.isNotBlank()) Text(status)
}
