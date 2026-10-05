package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import kotlinx.coroutines.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Call evidence is supplied by composition; rendering has no authority to locate the runtime. */
internal data class CallEvidenceSnapshot(val report: String, val audio: LiveCallAudioEvidence.Export?)
internal data class CallEvidenceActions(
    val armAudio: () -> Unit,
    val clearAudio: () -> Unit,
    val snapshot: () -> CallEvidenceSnapshot,
)

/** Snapshot the latest call before opening the document picker. One ZIP per phone test. */
@Composable
internal fun CallEvidenceExport(actions: CallEvidenceActions, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var pendingAudio by remember { mutableStateOf<LiveCallAudioEvidence.Export?>(null) }
    var status by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val report = pending
        val audio = pendingAudio
        pending = null
        pendingAudio = null
        if (uri != null && report != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ZipOutputStream(checkNotNull(context.contentResolver.openOutputStream(uri))).use { zip ->
                        zip.putNextEntry(ZipEntry("report.txt"))
                        zip.write(report.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        if (audio != null) {
                            zip.putNextEntry(ZipEntry("audio-evidence.txt"))
                            zip.write(audio.report.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                            audio.files.forEach { (name, bytes) ->
                                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                            }
                        }
                    }
                }
                status = "Call test ZIP saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Save failed: ${error.message}" }
        }
    }
    TextButton(enabled = enabled, onClick = {
        actions.armAudio()
        status = "Next call will retain a short audio sample in memory, including nearby speech. Export it with the call ZIP after ending the call."
    }) { Text("Record next call audio for diagnosis") }
    TextButton(enabled = enabled, onClick = {
        actions.clearAudio()
        status = "Diagnostic audio cleared; recording disarmed."
    }) { Text("Clear diagnostic audio") }
    TextButton(enabled = enabled, onClick = {
        val snapshot = actions.snapshot()
        pendingAudio = snapshot.audio
        pending = snapshot.report
        save.launch("jarvis-call-echo-${System.currentTimeMillis()}.zip")
    }) { Text("Save latest call test ZIP") }
    if (status.isNotEmpty()) Text(status)
}
