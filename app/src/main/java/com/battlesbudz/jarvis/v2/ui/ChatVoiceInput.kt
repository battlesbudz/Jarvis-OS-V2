package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.voice.ChatDictation
import kotlinx.coroutines.*

@Composable
internal fun ChatVoiceInput(enabled: Boolean, canSendAudio: Boolean, audioUnavailableReason: String?, createRecorder: () -> ChatDictation,
    onBusy: (Boolean) -> Unit, onAudio: suspend (ByteArray) -> Unit, onTranscript: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentTranscript by rememberUpdatedState(onTranscript)
    val currentAudio by rememberUpdatedState(onAudio)
    val currentError by rememberUpdatedState(onError)
    val currentBusy by rememberUpdatedState(onBusy)
    var job by remember { mutableStateOf<Job?>(null) }
    var recorder by remember { mutableStateOf<ChatDictation?>(null) }
    var recording by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    fun cancel() { job?.cancel() }
    fun start() {
        if (!currentEnabled || job?.isCompleted == false || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val session = createRecorder()
        recorder = session
        recording = true
        finishing = false
        val choice = CompletableDeferred<Boolean>()
        action = choice
        status = "Preparing voice input…"
        currentBusy(true)
        job = scope.launch {
            try {
                val pcm = session.record { update ->
                    scope.launch { if (recorder === session && !finishing) status = update }
                }
                ensureActive()
                if (!choice.isCompleted) status = "Recording ready · Stop for text or Send audio"
                val sendAudio = choice.await()
                if (sendAudio) currentAudio(pcm)
                else {
                    val text = session.transcribe(pcm) { update ->
                        scope.launch { if (recorder === session) status = update }
                    }
                    ensureActive()
                    currentTranscript(text)
                }
            } catch (timeout: TimeoutCancellationException) { currentError("Voice input timed out. Please try again.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { currentError(error.message ?: "Voice input failed. Please try again.") }
            finally {
                recorder = null
                recording = false
                finishing = false
                action = null
                currentBusy(false)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requestedHere = permissionPending
        permissionPending = false
        if (requestedHere) {
            if (granted) start() else currentError("Microphone permission is needed for voice input.")
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { permissionPending = false; cancel() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); job?.cancel() }
    }
    BackHandler(enabled = recording) { cancel() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (recording) {
            Text(status, modifier = Modifier.testTag("dictation_status"), style = MaterialTheme.typography.bodySmall)

            Row {
                TextButton(enabled = !finishing, onClick = {
                    finishing = true; status = "Transcribing…"; action?.complete(false); recorder?.finish()
                }, modifier = Modifier.testTag("dictation_stop")) { Text("Stop") }
                TextButton(enabled = !finishing && canSendAudio, onClick = {
                    finishing = true; status = "Sending audio…"; action?.complete(true); recorder?.finish()
                }, modifier = Modifier.testTag("dictation_send")) { Text("Send") }
                TextButton(onClick = { cancel() }, modifier = Modifier.testTag("dictation_cancel")) { Text("Cancel") }
            }
            if (!canSendAudio && audioUnavailableReason != null)
                Text(audioUnavailableReason, style = MaterialTheme.typography.bodySmall)
        } else TextButton(enabled = enabled && !permissionPending, onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else { permissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }, modifier = Modifier.testTag("chat_voice_input")) { Text("🎙 Voice input") }
    }
}
