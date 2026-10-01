package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.*

@Composable
internal fun VoiceInputSettings(enabled: Boolean, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(AsrEngine.selected(context)) }
    var captureProfile by remember { mutableStateOf(SpeechCaptureProfile.selected(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    var task by remember { mutableStateOf<Job?>(null) }
    Column {
        Text("Speech recognition: ${selected.label}")
        AsrEngine.entries.forEach { engine ->
            TextButton(enabled = enabled && !busy && selected != engine, onClick = {
                busy = true
                task = scope.launch {
                    try {
                        engine.prepare(context) { value -> scope.launch { message = value } }
                        AsrEngine.select(context, engine); selected = engine
                        message = "${engine.label} will handle your next voice call."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { message = error.message ?: "Download failed" }
                    finally { busy = false }
                }
            }) { Text("Use ${engine.label}") }
        }
        Text("Both transcribe your voice on this phone. Moonshine transcribes as you speak; Whisper confirms the text when you finish.", style = MaterialTheme.typography.bodySmall)
        Text("Microphone: ${captureProfile.label}")
        SpeechCaptureProfile.entries.forEach { profile ->
            TextButton(enabled = enabled && !busy && captureProfile != profile, onClick = {
                SpeechCaptureProfile.select(context, profile)
                captureProfile = profile
                message = "${profile.label} will apply when the next voice call opens its microphone."
            }) { Text("Use ${profile.label}") }
        }
        Text("Speech clarity preserves speech detail. Call noise reduction uses the phone's communication processing. Compare verified transcripts to find which works better on your phone.", style = MaterialTheme.typography.bodySmall)
        if (busy) TextButton(onClick = { task?.cancel(); message = "Stopped." }) { Text("Cancel") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
