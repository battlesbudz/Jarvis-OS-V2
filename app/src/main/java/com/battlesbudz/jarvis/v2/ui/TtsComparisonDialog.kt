package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.voice.*

@Composable
internal fun TtsComparisonDialog(
    latencyBenchmarks: VoiceLatencyBenchmarkActions,
    store: TtsComparisonStore, canChange: Boolean,
    onSelect: (TtsEngine) -> Boolean,
    onBenchmark: (TtsEngine?, (String) -> Unit, () -> Unit) -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(store.selectedEngine()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var records by remember { mutableStateOf(store.records().asReversed()) }
    var index by remember { mutableIntStateOf(0) }
    var gemmaReport by remember { mutableStateOf(latencyBenchmarks.gemmaResults.snapshot()) }
    fun runLatency(gemma: Boolean) {
        if (running) return
        running = true; copied = false
        val finished: () -> Unit = {
            running = false; records = store.records().asReversed(); index = 0
            gemmaReport = latencyBenchmarks.gemmaResults.snapshot()
        }
        if (gemma) latencyBenchmarks.compareGemma({ status = it }, finished)
        else latencyBenchmarks.compareOpenings(selected, { status = it }, finished)
    }
    fun run(engine: TtsEngine?) {
        if (running) return
        running = true
        copied = false
        onBenchmark(engine, { status = it }) {
            running = false
            records = store.records().asReversed()
            index = 0
        }
    }
    AlertDialog(onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Voice and response speed") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsEngine.entries.forEach { engine ->
                    OutlinedButton(onClick = { if (onSelect(engine)) selected = engine },
                        enabled = canChange && !running, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selected == engine) "✓ " else "") + engine.label)
                    }
                }
                Text("Selected voice is used for the next call. Miro downloads about 67 MB on first use. All voices then work offline.")
                if (selected == TtsEngine.PIPER_MIRO) Text("Miro by TigreGotico Lda / OpenVoiceOS. Non-commercial use only.", style = MaterialTheme.typography.bodySmall)
                if (!canChange) Text("End your call before changing voices or benchmarking.")
                HorizontalDivider()
                Text("Fixed-text benchmark", style = MaterialTheme.typography.titleMedium)
                Text("Each voice reads the same short reply, paragraph and story at normal speed. Gemma and the microphone stay idle. Downloads are excluded from timing. The full comparison can take several minutes.")
                Button(onClick = { run(selected) }, enabled = canChange && !running) { Text("Test selected voice") }
                OutlinedButton(onClick = { run(null) }, enabled = canChange && !running) { Text("Test all voices") }
                HorizontalDivider()
                Text("Response speed", style = MaterialTheme.typography.titleMedium)
                Text("Compare 2 and 4 CPU threads with 40- and 90-character openings using the same streamed text. Each setting runs twice in reverse order. Listen for smoothness and check synthesis speed; results do not change your current voice settings.")
                OutlinedButton(onClick = { runLatency(false) }, enabled = canChange && !running) {
                    Text("Compare voice speed")
                }
                Text("Compare Gemma GPU acceleration off, on, then off again. Includes warm runs and simulated battery tools; this does not change your phone or the acceleration used in calls. A model without MTP support will report an error for that test.")
                OutlinedButton(onClick = { runLatency(true) }, enabled = canChange && !running) {
                    Text("Compare Gemma acceleration")
                }
                if (running) Button(onClick = { onStop(); latencyBenchmarks.stop() }) { Text("Stop benchmark") }
                if (status.isNotBlank()) Text(status)
                TextButton(onClick = { records = store.records().asReversed(); index = 0 }) { Text("Refresh results") }
                val record = records.getOrNull(index)
                if (record != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("Newer") }
                        Text("${index + 1}/${records.size}")
                        TextButton(onClick = { index++ }, enabled = index < records.lastIndex) { Text("Older") }
                    }
                    Text(TtsComparisonStore.describe(record), style = MaterialTheme.typography.bodySmall)
                }
                Text("Compare completed benchmark results with the same sample name. Lower RTF is faster; below 1 keeps up with normal speech. Call results include competition from other models. Listen for pronunciation and voice quality too. Copy diagnostics includes the last 40 results.", style = MaterialTheme.typography.bodySmall)
                Text(gemmaReport, style = MaterialTheme.typography.bodySmall)
            }
        }, dismissButton = {
            TextButton(onClick = {
                val report = "Jarvis OS V2 voice benchmark diagnostics\n" +
                    "Selected voice: ${selected.label}\nStatus: $status\n\n" + store.snapshot() +
                    "\n\nGemma acceleration\n" + latencyBenchmarks.gemmaResults.snapshot()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis voice benchmark diagnostics", report))
                copied = true
            }) { Text(if (copied) "Copied diagnostics" else "Copy diagnostics") }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Done") } })
}
