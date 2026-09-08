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
    var status by remember { mutableStateOf("") }
    val busy = job?.isActive == true
    fun record() {
        if (!enabled || job?.isActive == true) return
        job = scope.launch {
            onBusyChanged(true)
            recording = null
            status = "Recording 8 seconds. Say ‘Open YouTube’ and ‘Hey Jarvis’."
            try {
                val bytes = withContext(Dispatchers.Default) {
                    AudioPathDiagnostic.record(AndroidAudioInput(this,
                        audioManager = context.getSystemService(AudioManager::class.java),
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
            if (event == Lifecycle.Event.ON_STOP) { job?.cancel(); recording = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); recording = null; onBusyChanged(false) }
    }
    Text("Check what Jarvis hears")
    Text("Uses the same microphone path as voice calls. Audio stays in memory and is discarded when you leave these settings.")
    TextButton(onClick = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, enabled = enabled && !busy) { Text("Record 8 seconds") }
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
    if (busy) TextButton(onClick = { job?.cancel() }) { Text("Stop audio test") }
    if (status.isNotBlank()) Text(status)
}
