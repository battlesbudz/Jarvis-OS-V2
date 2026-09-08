package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.voice.*

@Composable
internal fun TtsComparisonDialog(
    store: TtsComparisonStore, canChange: Boolean,
    onSelect: (TtsEngine) -> Boolean,
    onBenchmark: (TtsEngine?, (String) -> Unit, () -> Unit) -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(store.selectedEngine()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var records by remember { mutableStateOf(store.records().asReversed()) }
    var index by remember { mutableIntStateOf(0) }
    fun run(engine: TtsEngine?) {
        if (running) return
        running = true
        onBenchmark(engine, { status = it }) {
            running = false
            records = store.records().asReversed()
            index = 0
        }
    }
    AlertDialog(onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Voice model comparison") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsEngine.entries.forEach { engine ->
                    OutlinedButton(onClick = { if (onSelect(engine)) selected = engine },
                        enabled = canChange && !running, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selected == engine) "✓ " else "") + engine.label)
                    }
                }
                Text("Selected voice is used for the next call. First use downloads about 103 MB for INT8 Kokoro or 67 MB for Piper; both then work offline.")
                if (!canChange) Text("End your call before changing voices or benchmarking.")
                HorizontalDivider()
                Text("Fixed-text benchmark", style = MaterialTheme.typography.titleMedium)
                Text("Each voice reads the same short reply, paragraph and story at normal speed. Gemma and the microphone stay idle. Downloads are excluded from timing. The full comparison can take several minutes.")
                Button(onClick = { run(selected) }, enabled = canChange && !running) { Text("Test selected voice") }
                OutlinedButton(onClick = { run(null) }, enabled = canChange && !running) { Text("Test all three") }
                if (running) Button(onClick = onStop) { Text("Stop benchmark") }
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
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Done") } })
}
