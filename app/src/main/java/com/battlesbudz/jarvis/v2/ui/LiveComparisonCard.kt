package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import kotlinx.coroutines.*

@Composable
internal fun LiveComparisonCard(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by LiveComparison.status.collectAsState()
    val results by LiveComparison.results.collectAsState()
    var reference by remember { mutableStateOf("Explain why the sky looks blue during the day and orange near sunset in two sentences.") }
    var selected by remember { mutableStateOf(LiveComparison.Path.MOONSHINE) }
    var export by remember { mutableStateOf<LiveComparison.Trial?>(null) }
    var message by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val trial = export; export = null
        if (uri != null && trial != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { trial.writeZip(checkNotNull(context.contentResolver.openOutputStream(uri))) }
                message = "Comparison ZIP saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = "Save failed: ${error.message}" }
        }
    }
    Text("Live voice-call comparison")
    Text("Runs three real call turns per path. Keep the selected Gemma model, phone position, volume and question the same. " +
        "Read the reference once each time Jarvis listens. Setup and model reuse are reported separately; later turns retain call resources. " +
        "The reference is used only for scoring. This test speaks real answers and keeps interruption monitoring active.")
    OutlinedTextField(value = reference, onValueChange = { reference = it.take(1000) }, enabled = enabled, label = { Text("Words you will say") })
    LiveComparison.Path.entries.forEach { path ->
        TextButton(onClick = { selected = path }, enabled = enabled) { Text((if (selected == path) "✓ " else "") + path.label) }
    }
    TextButton(onClick = {
        try { LiveComparison.arm(selected, reference) } catch (error: Exception) { message = error.message.orEmpty() }
    }, enabled = enabled && reference.isNotBlank()) { Text("Arm three comparison turns") }
    TextButton(onClick = { LiveComparison.cancelPending() }) { Text("Cancel remaining comparison turns") }
    Text(status)
    Text("End the call after the third answer. Save each ZIP before closing the app; results are kept in memory only.")
    results.forEach { trial ->
        TextButton(onClick = { export = trial; save.launch("jarvis-live-comparison-${trial.request.path.name.lowercase()}-${trial.id}.zip") }) {
            Text("Save ZIP: ${trial.request.path.name} ${trial.request.repeat}/3")
        }
    }
    TextButton(onClick = { try { LiveComparison.clear() } catch (_: Exception) { message = "Cancel remaining turns before clearing." } }, enabled = enabled) { Text("Clear comparison results") }
    if (message.isNotEmpty()) Text(message)
}
