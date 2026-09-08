package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.voice.AsrComparisonStore

@Composable
internal fun AsrDiagnosticsDialog(
    store: AsrComparisonStore,
    onDismiss: () -> Unit
) {
    var records by remember { mutableStateOf(store.records().asReversed()) }
    var index by remember { mutableIntStateOf(0) }
    val record = records.getOrNull(index)
    var reference by remember(record?.optString("id")) { mutableStateOf(record?.optString("reference").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech recognition") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Moonshine Small Streaming is the speech recognizer. It downloads about 142 MB on first use, then works offline.")
                HorizontalDivider()
                Text("Compare turns", style = MaterialTheme.typography.titleMedium)
                Text("Compare turns recorded in similar conditions. Lower latency and word-error rate are better. These are live calls; timings include competition from Gemma and voice output.")
                TextButton(onClick = { records = store.records().asReversed(); index = 0 }) { Text("Refresh results") }
                if (record == null) Text("Results appear after a turn finishes. The last 20 stay available across calls and app restarts.")
                else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("Newer") }
                        Text("${index + 1} / ${records.size}")
                        TextButton(onClick = { index++ }, enabled = index < records.lastIndex) { Text("Older") }
                    }
                    Text(AsrComparisonStore.describe(record), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = reference, onValueChange = { reference = it.take(4000) },
                        label = { Text("What I actually said") }, minLines = 2, maxLines = 5)
                    TextButton(onClick = {
                        store.setReference(record.optString("id"), reference)
                        records = store.records().asReversed()
                    }) { Text("Save reference and score") }
                    Text("Word-error rate compares exact words, ignoring case and punctuation. Keep false starts and corrections. It does not score intent; numbers and contractions can differ. An empty reference leaves accuracy unscored.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("First-partial timing begins when speech is detected. Finalization measures the last ASR flush. Final-to-text/playback begins after that flush and excludes the 3-second silence wait. Playback start measures Android playback-head movement, not an acoustic measurement; older records measured submission. Decode factor includes all audio fed, including silence; below 1 means decoding cost less than the audio duration.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Copy diagnostics on the call screen includes these comparisons.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
