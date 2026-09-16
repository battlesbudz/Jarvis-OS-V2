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
    onBenchmark: (TtsEngine?, TtsBenchmarkProfile, (String) -> Unit, () -> Unit) -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val load by latencyBenchmarks.loadTests.state.collectAsState()
    var loadNotes by remember { mutableStateOf("") }
    val setup by latencyBenchmarks.setupTests.state.collectAsState()
    DisposableEffect(latencyBenchmarks.setupTests) {
        onDispose { latencyBenchmarks.setupTests.cancel(); latencyBenchmarks.loadTests.cancel() }
    }
    var copiedId by remember { mutableStateOf<String?>(null) }
    var copiedSuiteId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(store.selectedEngine()) }
    var appliedProfile by remember(selected) { mutableStateOf(store.callProfile(selected)) }
    var profile by remember(selected) { mutableStateOf(appliedProfile ?: TtsBenchmarkProfile(openingChars = 320)) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    fun savedRuns() = store.records().filter { it.optString("source") != "voice-call" }.asReversed()
    var records by remember { mutableStateOf(savedRuns()) }
    var index by remember { mutableIntStateOf(0) }
    var gemmaRecords by remember { mutableStateOf(latencyBenchmarks.gemmaResults.records().asReversed()) }
    var gemmaIndex by remember { mutableIntStateOf(0) }
    fun copy(label: String, report: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, report))
    }
    fun runLatency(gemma: Boolean, inputs: Boolean = false) {
        if (running) return
        val startedAt = System.currentTimeMillis()
        running = true; copiedId = null
        if (gemma) gemmaRecords = emptyList() else records = emptyList()
        val finished: () -> Unit = {
            running = false
            if (gemma) {
                gemmaRecords = latencyBenchmarks.gemmaResults.records().filter { it.optLong("at_ms") >= startedAt }.asReversed()
                gemmaIndex = 0
            } else { records = savedRuns().filter { it.optLong("atMs") >= startedAt }; index = 0 }
        }
        if (inputs) latencyBenchmarks.compareGemmaLatency({ status = it }, finished)
        else if (gemma) latencyBenchmarks.compareGemma({ status = it }, finished)
        else latencyBenchmarks.compareOpenings(selected, { status = it }, finished)
    }
    fun run(engine: TtsEngine?) {
        if (running) return
        val startedAt = System.currentTimeMillis()
        running = true
        copiedId = null; records = emptyList()
        val finished: () -> Unit = {
            running = false
            records = savedRuns().filter { it.optLong("atMs") >= startedAt }
            index = 0
        }
        onBenchmark(engine, profile, { status = it }, finished)
    }
    AlertDialog(onDismissRequest = { if (!running && !setup.busy && !load.busy) onDismiss() },
        title = { Text("Voice and response speed") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Structured voice tests", style = MaterialTheme.typography.titleMedium)
                Text("P1 · Setup and profile recovery. No audio is generated in this pack. P2 adds fixed audio comparisons; P3–P7 are planned.")
                Text(setup.message)
                Button(onClick = { latencyBenchmarks.setupTests.start() }, enabled = canChange && !running && !setup.busy && !load.busy) {
                    Text("Start P1 setup")
                }
                if (setup.busy) {
                    Text(VoiceTestPacks.reference.label)
                    TextButton(onClick = { latencyBenchmarks.setupTests.complete() }, enabled = setup.ready) { Text("Complete P1 setup") }
                    TextButton(onClick = { latencyBenchmarks.setupTests.cancel() }) { Text("Cancel P1 setup") }
                }
                TextButton(onClick = { copy("Jarvis P1 setup report", setup.report) }, enabled = !setup.busy && setup.report.isNotBlank()) { Text("Copy P1 report") }
                HorizontalDivider()
                Text("P2 · Fixed audio comparisons", style = MaterialTheme.typography.titleMedium)
                Text(load.message)
                Button(onClick = { loadNotes = ""; latencyBenchmarks.loadTests.start() }, enabled = canChange && !running && !setup.busy && !load.busy) { Text("Start P2 screening") }
                Button(onClick = { loadNotes = ""; latencyBenchmarks.loadTests.start(long = true) }, enabled = canChange && !running && !setup.busy && !load.busy) { Text("Start P2 long narration") }
                if (load.waiting) {
                    OutlinedTextField(value = loadNotes, onValueChange = { loadNotes = it }, label = { Text("Listening notes (optional)") })
                    Button(onClick = { latencyBenchmarks.loadTests.next(loadNotes); loadNotes = "" }) { Text("Continue") }
                    if (load.canReplay) TextButton(onClick = { latencyBenchmarks.loadTests.replay() }) { Text("Replay this source at 0.9x") }
                }
                if (load.busy) TextButton(onClick = { latencyBenchmarks.loadTests.cancel() }) { Text("Cancel P2") }
                TextButton(onClick = { copy("Jarvis P2 report", load.report) }, enabled = !load.busy && load.report.isNotBlank()) { Text("Copy P2 report") }
                HorizontalDivider()
                if (!setup.busy && !load.busy) {
                TtsEngine.entries.forEach { engine ->
                    OutlinedButton(onClick = { if (onSelect(engine)) selected = engine },
                        enabled = canChange && !running, modifier = Modifier.fillMaxWidth()) {
                        Text((if (selected == engine) "✓ " else "") + engine.label)
                    }
                }
                Text("Piper Northern English Male downloads about 67 MB during setup, then works offline.")
                if (selected == TtsEngine.PIPER_NORTHERN) Text("Northern English Male: OpenSLR 83 dataset (CC BY-SA 4.0). Voice model card is retained on the phone.", style = MaterialTheme.typography.bodySmall)
                if (!canChange) Text("End your call before changing voices or benchmarking.")
                HorizontalDivider()
                Text("Speech tuning", style = MaterialTheme.typography.titleMedium)
                Text("Try a profile in a benchmark, or apply it to your next voice call. Your Piper settings are saved for calls. Benchmarks read the same samples twice with Gemma and the microphone idle.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4).forEach { threads ->
                        FilterChip(selected = profile.threads == threads, enabled = !running,
                            onClick = { profile = profile.copy(threads = threads) }, label = { Text("$threads threads") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 0.9f, 0.85f).forEach { speed ->
                        FilterChip(selected = profile.playbackSpeed == speed, enabled = !running,
                            onClick = { profile = profile.copy(playbackSpeed = speed) }, label = { Text("${speed}×") })
                    }
                }
                if (selected == TtsEngine.PIPER_NORTHERN) {
                    FilterChip(selected = profile.piperPassages && profile.openingChars == 320, enabled = !running,
                        onClick = { profile = profile.copy(openingChars = 320) },
                        label = { Text("Piper · longer passages") })
                    FilterChip(selected = profile.piperPassages && profile.openingChars == 160, enabled = !running,
                        onClick = { profile = profile.copy(openingChars = 160) },
                        label = { Text("Piper · faster opening (compare)") })
                    Text("Faster opening targets 160 characters, or releases a complete sentence of at least 60 characters after 750 ms of text collection. Later passages stay longer. Test the voice before applying it to calls; your saved setting stays unchanged.")
                    Text("Keeps short replies together and groups longer replies at sentence boundaries around 320 characters, up to 640 per generation. Piper processes each passage together. More text context can delay the start; listen for voice consistency. Choose Test selected voice, then Apply to voice calls if you prefer it.")
                }
                listOf<Int?>(40, 60, 90, null).forEach { opening ->
                    FilterChip(selected = profile.openingChars == opening, enabled = !running,
                        onClick = { profile = profile.copy(openingChars = opening) },
                        label = { Text(opening?.let { "$it-character opening" } ?: if (selected == TtsEngine.PIPER_NORTHERN) "Piper · wait for full reply" else "Synthesize full text before playback") })
                }
                Text("Profile: ${profile.label}")
                Text("Voice calls: ${appliedProfile?.label ?: "Piper · 320-character passages · normal speed"}")
                Button(onClick = {
                    store.setCallProfile(selected, profile)
                    appliedProfile = profile
                    status = "Saved for ${selected.label}. Applies to your next call."
                }, enabled = canChange && !running) {
                    Text("Apply to voice calls")
                }
                TextButton(onClick = {
                    store.setCallProfile(selected, null)
                    appliedProfile = null
                    status = "Original call defaults restored for ${selected.label}."
                }, enabled = canChange && !running && appliedProfile != null) { Text("Restore call defaults") }
                Text("Opening sizes are targets at natural word/clause boundaries. Full text waits for the entire input. Piper retains a 640-character generation limit even in full-reply mode. 0.9× slows playback without lowering pitch. Full text is a buffered baseline.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run(selected) }, enabled = canChange && !running) { Text("Test selected voice") }
                HorizontalDivider()
                Text("Response speed", style = MaterialTheme.typography.titleMedium)
                Text("The full comparison tests Piper with 24 profiles: 2/4 threads, 40/60/90/160/320-character openings or full text, and 1.0×/0.9× playback. That is ${TtsBenchmarkProfile.comparisonRunCount} text runs including repeats and can take a long time. Heat status is recorded but never pauses or stops a test. Stop preserves completed results. Running a comparison keeps your applied call profile.")
                OutlinedButton(onClick = { runLatency(false) }, enabled = canChange && !running) {
                    Text("Compare Piper profiles")
                }
                Text("Compare Gemma GPU acceleration off, on, then off again. Includes warm runs and simulated battery tools; this does not change your phone or the acceleration used in calls. A model without MTP support will report an error for that test.")
                OutlinedButton(onClick = { runLatency(true) }, enabled = canChange && !running) {
                    Text("Compare Gemma acceleration")
                }
                Text("Compare Gemma with the last spoken request: short text, conversation text, and the same conversation plus audio. Runs three passes with speech engines idle. The last recording is kept only in app memory; results save generated text and timings, not audio. Complete a turn and end the call first. This does not change live input routing.")
                OutlinedButton(onClick = { runLatency(true, inputs = true) }, enabled = canChange && !running) {
                    Text("Compare Gemma text vs audio")
                }
                if (running) Button(onClick = { onStop(); latencyBenchmarks.stop() }) { Text("Stop benchmark") }
                if (status.isNotBlank()) Text(status)
                TextButton(onClick = { records = savedRuns(); index = 0; copiedId = null }, enabled = !running) { Text("Browse saved text runs") }
                val record = records.getOrNull(index)
                if (record != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("Newer") }
                        Text("${index + 1}/${records.size}")
                        TextButton(onClick = { index++ }, enabled = index < records.lastIndex) { Text("Older") }
                    }
                    val suiteRuns = store.suiteRecords(record)
                    if (suiteRuns.isNotEmpty()) {
                        Button(enabled = !running && !setup.busy && !load.busy, onClick = {
                            copy("Jarvis whole voice comparison suite", store.suiteDiagnosticReport(record))
                            copiedSuiteId = record.optString("suite_id")
                        }) {
                            Text(if (copiedSuiteId == record.optString("suite_id")) "Copied whole suite · ${suiteRuns.size} runs"
                                else "Copy whole suite · ${suiteRuns.size} runs")
                        }
                    }
                    Text(TtsComparisonStore.describe(record), style = MaterialTheme.typography.bodySmall)
                    BenchmarkAudioExport(record, enabled = !running)
                }
                Text("Compare the same sample and pass. Prioritize completed, thermally clean runs with the requested speed applied: lowest estimated supply gaps first, then lowest first-text-to-playback time. Effective RTF accounts for playback speed; below 1 can keep up. Playback timing detects the first non-silent audio, not a word recognized by a microphone. Gap values are estimates; listen for unnatural pauses too. Copy whole suite includes every saved run in that comparison, in execution order. Copy this text run exports only the displayed result.", style = MaterialTheme.typography.bodySmall)
                if (gemmaRecords.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Gemma text run", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { gemmaIndex-- }, enabled = gemmaIndex > 0) { Text("Newer") }
                        Text("${gemmaIndex + 1}/${gemmaRecords.size}")
                        TextButton(onClick = { gemmaIndex++ }, enabled = gemmaIndex < gemmaRecords.lastIndex) { Text("Older") }
                    }
                    gemmaRecords.getOrNull(gemmaIndex)?.let { gemma ->
                        Text(gemma.toString(2), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { copy("Jarvis Gemma text-run diagnostics", gemma.toString(2)) }, enabled = !running) {
                            Text("Copy this Gemma text run")
                        }
                    }
                }
                }
            }
        }, dismissButton = {
            TextButton(onClick = {
                records.getOrNull(index)?.let { record ->
                    copy("Jarvis voice text-run diagnostics", TtsComparisonStore.diagnosticReport(record))
                    copiedId = record.optString("id")
                }
            }, enabled = !running && !setup.busy && !load.busy && records.getOrNull(index) != null) {
                Text(if (copiedId != null && copiedId == records.getOrNull(index)?.optString("id"))
                    "Copied this run" else "Copy this text run")
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !running && !setup.busy && !load.busy) { Text("Done") } })
}
