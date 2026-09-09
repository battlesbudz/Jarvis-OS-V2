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
import kotlinx.coroutines.launch

@Composable
internal fun TtsComparisonDialog(
    latencyBenchmarks: VoiceLatencyBenchmarkActions,
    store: TtsComparisonStore, canChange: Boolean,
    onSelect: (TtsEngine) -> Boolean,
    onBenchmark: (TtsEngine?, TtsBenchmarkProfile, (String) -> Unit, () -> Unit) -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copiedId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(store.selectedEngine()) }
    var appliedProfile by remember(selected) { mutableStateOf(store.callProfile(selected)) }
    var profile by remember(selected) { mutableStateOf(appliedProfile ?: if (selected == TtsEngine.POCKET_PAUL)
        TtsBenchmarkProfile(threads = 2, openingChars = null, nativeStreaming = true) else TtsBenchmarkProfile()) }
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
    fun runLatency(gemma: Boolean) {
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
        if (gemma) latencyBenchmarks.compareGemma({ status = it }, finished)
        else latencyBenchmarks.compareOpenings(selected, { status = it }, finished)
    }
    fun run(engine: TtsEngine?, isolation: Boolean = false) {
        if (running) return
        val startedAt = System.currentTimeMillis()
        running = true
        copiedId = null; records = emptyList()
        val finished: () -> Unit = {
            running = false
            records = savedRuns().filter { it.optLong("atMs") >= startedAt }
            index = 0
        }
        if (isolation) latencyBenchmarks.comparePaulIsolation({ status = it }, finished)
        else onBenchmark(engine, profile, { status = it }, finished)
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
                Text("Selected voice is used for the next call. Pocket Paul downloads about 99 MB on first use. All voices then work offline.")
                if (selected == TtsEngine.POCKET_PAUL) Text("Paul: Kyutai / VCTK p259 (CC BY 4.0). The opening ummm is bundled; follow-up acknowledgements are prepared once and cached.", style = MaterialTheme.typography.bodySmall)
                if (selected == TtsEngine.POCKET_PAUL) OutlinedButton(enabled = canChange && !running,
                    onClick = {
                        running = true
                        scope.launch {
                            try {
                                VoiceCues.playAcknowledgement(SpeechAudio(FillerPhrases.INITIAL, 24000,
                                    PaulOpeningAudio.load(context.assets), 0), { false }, { false }, {})
                                status = "Opening preview finished. Calls use this same clip."
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (error: Exception) { status = error.message ?: "Preview failed." }
                            finally { running = false }
                        }
                    }) { Text("Preview Paul’s opening ummm") }
                if (!canChange) Text("End your call before changing voices or benchmarking.")
                HorizontalDivider()
                Text("Speech tuning", style = MaterialTheme.typography.titleMedium)
                Text("Try a profile in a benchmark, or apply it to your next voice call. Settings are saved separately for each voice. Benchmarks read the same samples twice with Gemma and the microphone idle.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4).forEach { threads ->
                        FilterChip(selected = profile.threads == threads, enabled = !running,
                            onClick = { profile = profile.copy(threads = threads) }, label = { Text("$threads threads") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 0.9f).forEach { speed ->
                        FilterChip(selected = profile.playbackSpeed == speed, enabled = !running,
                            onClick = { profile = profile.copy(playbackSpeed = speed) }, label = { Text("${speed}×") })
                    }
                }
                listOf<Int?>(40, 60, 90, null).forEach { opening ->
                    FilterChip(selected = !profile.nativeStreaming && profile.openingChars == opening, enabled = !running,
                        onClick = { profile = profile.copy(openingChars = opening, nativeStreaming = false, resetDecoder = true, leadingPeriod = true, bufferMs = 200) },
                        label = { Text(opening?.let { "$it-character opening" } ?: "Synthesize full text before playback") })
                }
                FilterChip(selected = profile.nativeStreaming, enabled = !running,
                    onClick = { profile = profile.copy(openingChars = null, nativeStreaming = true) },
                    label = { Text("Paul · native audio streaming (live call behavior)") })
                if (selected == TtsEngine.POCKET_PAUL && profile.nativeStreaming) {
                    Text("Paul stability", style = MaterialTheme.typography.titleMedium)
                    listOf(true, false).forEach { reset ->
                        FilterChip(selected = profile.resetDecoder == reset, enabled = !running,
                            onClick = { profile = profile.copy(resetDecoder = reset) },
                            label = { Text(if (reset) "Fresh decoder per sentence" else "Continuous decoder · previous mode") })
                    }
                    FilterChip(selected = profile.leadingPeriod, enabled = !running,
                        onClick = { profile = profile.copy(leadingPeriod = !profile.leadingPeriod) },
                        label = { Text("Leading period for Paul’s opening") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 200, 400).forEach { buffer ->
                            FilterChip(selected = profile.bufferMs == buffer, enabled = !running,
                                onClick = { profile = profile.copy(bufferMs = buffer) }, label = { Text("${buffer}ms") })
                        }
                    }
                    Text("Both modes stream audio within each sentence. The fresh mode resets decoder and sampling state together. Calls can add up to 400ms of extra cushion after underruns; benchmarks keep the selected cushion fixed. Zero disables the cushion. The leading period is an experimental pronunciation aid.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { run(null) }, enabled = canChange && !running) {
                        Text("Compare Paul period on/off · same text")
                    }
                }
                if (selected == TtsEngine.POCKET_PAUL) {
                    OutlinedButton(onClick = { run(TtsEngine.POCKET_PAUL, isolation = true) }, enabled = canChange && !running) {
                        Text("Compare Paul source audio · 14 runs")
                    }
                    Text("Compares a short opening and paragraph as one or grouped submissions, then compares fresh versus retained synthesis state (decoder, RNG and chunk startup together). Includes ‘I understand.’ alone. Fixed 2 threads, 1.0× speed and 200ms cushion; text is ready upfront. Each case runs twice in reversed order. Exports include source pauses and levels. Listen for missing words and voice changes; this test leaves your call settings intact.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Profile: ${profile.label}")
                Text("Voice calls: ${appliedProfile?.label ?: "Original adaptive defaults"}")
                if (selected == TtsEngine.POCKET_PAUL) Text(appliedProfile?.stabilityLabel ?: "Default: fresh decoder per sentence group · no leading period · adaptive 200ms cushion")
                Button(onClick = {
                    store.setCallProfile(selected, profile)
                    appliedProfile = profile
                    status = "Saved for ${selected.label}. Applies to your next call."
                }, enabled = canChange && !running && (!profile.nativeStreaming || selected == TtsEngine.POCKET_PAUL)) {
                    Text("Apply to voice calls")
                }
                TextButton(onClick = {
                    store.setCallProfile(selected, null)
                    appliedProfile = null
                    status = "Original call defaults restored for ${selected.label}."
                }, enabled = canChange && !running && appliedProfile != null) { Text("Restore call defaults") }
                Text("Opening sizes are targets at natural word/clause boundaries. Full text waits for the entire input and synthesizes it in one call. 0.9× slows playback without lowering pitch. Paul’s native profile streams decoded audio as generation proceeds and uses the selected decoder state mode. Full text is a buffered baseline. Kokoro profiles add no startup wait; Paul native profiles use the selected cushion.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run(selected) }, enabled = canChange && !running && (!profile.nativeStreaming || selected == TtsEngine.POCKET_PAUL)) { Text("Test selected voice") }
                OutlinedButton(onClick = { run(null) }, enabled = canChange && !running && !profile.nativeStreaming) { Text("Compare all voices · this profile") }
                HorizontalDivider()
                Text("Response speed", style = MaterialTheme.typography.titleMedium)
                Text("The full comparison tests Kokoro and Pocket Paul with all 16 profiles: 2/4 threads, 40/60/90-character openings or full text, and 1.0×/0.9× playback. It also includes Paul’s 4 native streaming profiles. That is ${TtsBenchmarkProfile.comparisonRunCount} text runs including repeats and can take a long time. Heat status is recorded but never pauses or stops a test. Stop preserves completed results. Running a comparison keeps your applied call profile.")
                OutlinedButton(onClick = { runLatency(false) }, enabled = canChange && !running) {
                    Text("Compare all profiles · all voices")
                }
                Text("Compare Gemma GPU acceleration off, on, then off again. Includes warm runs and simulated battery tools; this does not change your phone or the acceleration used in calls. A model without MTP support will report an error for that test.")
                OutlinedButton(onClick = { runLatency(true) }, enabled = canChange && !running) {
                    Text("Compare Gemma acceleration")
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
                    Text(TtsComparisonStore.describe(record), style = MaterialTheme.typography.bodySmall)
                    BenchmarkAudioExport(record, enabled = !running)
                }
                Text("Compare the same sample and pass. Prioritize completed, thermally clean runs with the requested speed applied: lowest estimated supply gaps first, then lowest first-text-to-playback time. Effective RTF accounts for playback speed; below 1 can keep up. Playback timing detects the first non-silent audio, not a word recognized by a microphone. Gap values are estimates; listen for unnatural pauses too. Copy exports only the displayed text run.", style = MaterialTheme.typography.bodySmall)
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
        }, dismissButton = {
            TextButton(onClick = {
                records.getOrNull(index)?.let { record ->
                    copy("Jarvis voice text-run diagnostics", TtsComparisonStore.diagnosticReport(record))
                    copiedId = record.optString("id")
                }
            }, enabled = !running && records.getOrNull(index) != null) {
                Text(if (copiedId != null && copiedId == records.getOrNull(index)?.optString("id"))
                    "Copied this run" else "Copy this text run")
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Done") } })
}
