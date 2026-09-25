package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.voice.EchoArchiveReader
import com.battlesbudz.jarvis.v2.voice.EchoRecognitionReplay
import com.battlesbudz.jarvis.v2.voice.AudioPathDiagnostic
import com.battlesbudz.jarvis.v2.voice.DuplexAudioEvidence
import com.battlesbudz.jarvis.v2.voice.DuplexEchoDiagnostic
import kotlinx.coroutines.*

/** A single focused acoustic test, available only while the call/wake microphone is idle. */
@Composable
fun DuplexEchoDiagnosticCard(enabled: Boolean, onBusyChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<DuplexAudioEvidence.Result>()) }
    var pendingExport by remember { mutableStateOf(listOf<DuplexAudioEvidence.Result>()) }
    var requested by remember { mutableStateOf(DuplexEchoDiagnostic.Scenario.JARVIS_ONLY) }
    var profile by remember {
        mutableStateOf(if (android.os.Build.VERSION.SDK_INT >= 31)
            DuplexEchoDiagnostic.RouteProfile.COMMUNICATION_SPEAKER else DuplexEchoDiagnostic.RouteProfile.CURRENT_MEDIA)
    }
    var replayed by remember { mutableStateOf(false) }
    val archiveLabel = if (replayed) "asr-replay" else profile.id
    val busy = job?.isActive == true
    fun runTest() {
        if (!enabled || job?.isActive == true) return
        val scenario = requested
        job = scope.launch {
            onBusyChanged(true)
            results = if (replayed) emptyList() else results.filterNot { it.scenario == scenario.id }
            replayed = false
            try {
                val result = DuplexEchoDiagnostic.run(context, scenario, profile) { message -> scope.launch { status = message } }
                results = results + result
                status = "${scenario.label} finished. You can play the microphone recording or save the evidence."
            } catch (cancelled: CancellationException) { status = "Echo test stopped."; throw cancelled }
            catch (error: Exception) { status = "Echo test failed: ${error.message}" }
            finally { onBusyChanged(false); job = null }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) runTest() else status = "Microphone permission is needed for the echo test."
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = pendingExport
        pendingExport = emptyList()
        if (uri != null && snapshot.isNotEmpty()) {
            job = scope.launch {
                onBusyChanged(true)
                try {
                    withContext(Dispatchers.IO) {
                        val output = checkNotNull(context.contentResolver.openOutputStream(uri)) { "Could not open the destination." }
                        DuplexAudioEvidence.writeArchive(output, snapshot)
                    }
                    status = "Echo evidence saved. Share that ZIP with the diagnostics."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status = "Could not save evidence: ${error.message}" }
                finally { onBusyChanged(false); job = null }
            }
        }
    }
    val replay = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && enabled && job?.isActive != true) {
            job = scope.launch {
                onBusyChanged(true)
                results = emptyList()
                replayed = true
                try {
                    val recordings = withContext(Dispatchers.IO) {
                        EchoArchiveReader.read(checkNotNull(context.contentResolver.openInputStream(uri)))
                    }
                    results = EchoRecognitionReplay.run(context, recordings) { message -> scope.launch { status = message } }
                    status = "Saved audio compared. Save the replay ZIP or copy its diagnostics. No new recording was made."
                } catch (cancelled: CancellationException) { status = "Replay stopped."; throw cancelled }
                catch (error: Exception) { status = "Replay failed: ${error.message}" }
                finally { onBusyChanged(false); job = null }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                job?.cancel()
                // A Save action has already snapshotted its bounded evidence before opening the picker.
                results = emptyList()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); results = emptyList() }
    }
    Text("Echo test — Phase D")
    Text("Run these three 10-second tests at your normal call volume. Keep the phone in the same place. " +
        "Audio stays in memory until you choose Save; leaving this screen clears it. " +
        "Save each test as a ZIP before changing routes. Spoken Stop and Hey Jarvis are recorded for analysis; " +
        "use Stop echo test below to cancel this fixed recording.")
    TextButton(onClick = { replay.launch(arrayOf("application/zip", "application/octet-stream")) },
        enabled = enabled && !busy && results.isEmpty()) { Text("Replay saved echo ZIP — Moonshine and Whisper") }
    Text("Replay uses saved audio without the microphone. It compares the old diagnostic path, call input gates, " +
        "corrected Moonshine diagnostic path, and Whisper. Missing recognition models download first.")
    Text("Route: ${profile.label}")
    DuplexEchoDiagnostic.RouteProfile.entries.forEach { option ->
        TextButton(onClick = { profile = option }, enabled = enabled && !busy && results.isEmpty() &&
            (option != DuplexEchoDiagnostic.RouteProfile.COMMUNICATION_SPEAKER || android.os.Build.VERSION.SDK_INT >= 31)) {
            Text(option.label)
        }
    }
    Text(if (profile == DuplexEchoDiagnostic.RouteProfile.COMMUNICATION_SPEAKER)
        "D2 uses the phone speaker and call volume. Disconnect headphones and Bluetooth audio for this comparison. " +
            "The test releases its route afterward; normal calls still use the current route."
    else "D1 baseline uses the current media route and media volume. Keep placement and perceived volume comparable.")
    if (results.isNotEmpty()) Text("Save the evidence, then clear it to select another route.")
    DuplexEchoDiagnostic.Scenario.entries.forEach { scenario ->
        TextButton(onClick = {
            requested = scenario
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runTest()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }, enabled = enabled && !busy) { Text("Test: ${scenario.label}") }
        Text(scenario.instruction)
    }
    results.forEach { result ->
        TextButton(onClick = {
            pendingExport = listOf(result)
            save.launch("jarvis-echo-${archiveLabel}-${result.scenario}-${System.currentTimeMillis()}.zip")
        }, enabled = enabled && !busy) { Text("Save ZIP: ${result.scenario.replace('_', ' ')}") }
        TextButton(onClick = {
            job = scope.launch {
                onBusyChanged(true)
                try { AudioPathDiagnostic.play(result.microphone); status = "Microphone playback finished." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status = "Playback failed: ${error.message}" }
                finally { onBusyChanged(false); job = null }
            }
        }, enabled = enabled && !busy) { Text("Play microphone: ${result.scenario.replace('_', ' ')}") }
    }
    if (results.isNotEmpty()) {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Jarvis echo evidence", results.joinToString("\n\n") { it.report }))
            status = "Echo diagnostics copied."
        }, enabled = !busy) { Text("Copy echo diagnostics") }
        TextButton(onClick = {
            pendingExport = results.toList()
            save.launch("jarvis-echo-${archiveLabel}-${System.currentTimeMillis()}.zip")
        }, enabled = enabled && !busy) { Text("Save echo recordings and diagnostics") }
    }
    if (results.isNotEmpty()) TextButton(onClick = { results = emptyList(); status = "Echo recordings cleared." },
        enabled = enabled && !busy) { Text("Clear echo recordings") }
    if (busy) TextButton(onClick = { job?.cancel() }) { Text("Stop echo test") }
    if (status.isNotBlank()) Text(status)
}
