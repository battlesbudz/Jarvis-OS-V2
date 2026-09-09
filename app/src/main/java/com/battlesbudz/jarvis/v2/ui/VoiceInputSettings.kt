package com.battlesbudz.jarvis.v2.ui

import android.content.Context
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
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    var task by remember { mutableStateOf<Job?>(null) }
    fun test() {
        busy = true
        task = scope.launch {
            try { message = AsrRoomComparison.run(context) { value -> scope.launch { message = value } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Voice input test failed" }
            finally { busy = false }
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && enabled && !busy) test() else if (!granted) message = "Microphone permission is required."
    }
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
                    catch (error: Exception) { message = error.message ?: "Download failed" }
                    finally { busy = false }
                }
            }) { Text("Use ${engine.label}") }
        }
        Text("Moonshine shows words while you speak. Whisper base.en transcribes after you finish. Both run on this phone.", style = MaterialTheme.typography.bodySmall)
        Text("Speaker preference learns automatically from consistent speech after separate activations. Short or uncertain samples stay permissive while it learns.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled && !busy, onClick = { permission.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("Compare both on one 8-second recording") }
        TextButton(enabled = enabled && !busy, onClick = {
            context.getSharedPreferences("speaker_preference_v1", Context.MODE_PRIVATE).edit().clear().apply()
            message = "Learned voice preference cleared. It will learn again after your next activations."
        }) { Text("Reset learned voice preference") }
        if (busy) TextButton(onClick = { task?.cancel(); message = "Stopped." }) { Text("Cancel") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
