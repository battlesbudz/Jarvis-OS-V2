package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@Composable
internal fun BenchmarkAudioExport(record: JSONObject, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(record.optString("id")) { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
                }
                status = "Audio and boundary log saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Export failed: ${error.message}" }
            finally { busy = false }
        } else busy = false
    }
    if (record.optString("audio_file").endsWith(".wav")) {
        TextButton(enabled = enabled && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    pending = withContext(Dispatchers.IO) {
                        val name = File(record.getString("audio_file")).name
                        val directory = File(context.cacheDir, "voice-benchmarks")
                        val wav = File(directory, name)
                        check(wav.isFile) { "This recording expired. Run the test again." }
                        ByteArrayOutputStream().use { bytes ->
                            ZipOutputStream(bytes).use { zip ->
                                for (file in listOf(wav, File(directory, name.removeSuffix(".wav") + ".txt"))) {
                                    zip.putNextEntry(ZipEntry(file.name))
                                    file.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                                zip.putNextEntry(ZipEntry("settings-and-results.json"))
                                zip.write(record.toString(2).toByteArray())
                                zip.closeEntry()
                            }
                            bytes.toByteArray()
                        }
                    }
                    launcher.launch("jarvis-voice-${record.optString("sample")}-${record.optString("id")}.zip")
                } catch (cancelled: CancellationException) { busy = false; throw cancelled }
                catch (error: Exception) { busy = false; status = "Export failed: ${error.message}" }
            }
        }) { Text("Export this run’s audio + diagnostics") }
        if (status.isNotBlank()) Text(status)
    }
}
