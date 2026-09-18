package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff
import kotlinx.coroutines.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Snapshot the latest call before opening the document picker. One ZIP per phone test. */
@Composable
internal fun CallEvidenceExport(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val report = pending
        pending = null
        if (uri != null && report != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ZipOutputStream(checkNotNull(context.contentResolver.openOutputStream(uri))).use { zip ->
                        zip.putNextEntry(ZipEntry("report.txt"))
                        zip.write(report.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
                status = "Call test ZIP saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Save failed: ${error.message}" }
        }
    }
    TextButton(enabled = enabled, onClick = {
        pending = "Jarvis live call echo test\n" +
            "scope=retained_call_diagnostics microphoneRecording=false acousticCancellation=not_measured\n" +
            "Save one ZIP after each call test, before starting the next call.\n\n" +
            JarvisRuntime.get(context.applicationContext).diagnosticRecorder.snapshot() +
            "\n\n" + MicrophoneHandoff.diagnostics()
        save.launch("jarvis-call-echo-${System.currentTimeMillis()}.zip")
    }) { Text("Save latest call test ZIP") }
    if (status.isNotEmpty()) Text(status)
}
