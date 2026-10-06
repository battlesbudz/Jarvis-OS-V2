package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.PhoneCheck
import com.battlesbudz.jarvis.v2.eval.AndroidReliabilityReportStore
import com.battlesbudz.jarvis.v2.eval.ToolReliabilityBenchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Model browsing/storage UI reused by first-run setup and conversation settings. */
@Composable
internal fun ModelSelectionSection(
    state: ModelSetupState,
    enabled: Boolean,
    benchmarkStore: com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore,
    onOpenMemory: () -> Unit
) {
    val phoneContext = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var phone by remember { mutableStateOf(PhoneCheck.read(phoneContext)) }
    Column {
        Text("AI model", style = MaterialTheme.typography.titleMedium)
        var expanded by remember { mutableStateOf(false) }
        var confirmingDelete by remember { mutableStateOf(false) }
        var detailsOpen by remember(state.selectedModel.id) { mutableStateOf(false) }
        var storageRevision by remember { mutableStateOf(0) }
        val reliabilityStore = remember(phoneContext) { AndroidReliabilityReportStore(phoneContext) }
        var reliabilityRunning by remember { mutableStateOf(false) }
        var reliabilityProgress by remember { mutableStateOf<String?>(null) }
        var reliabilityRevision by remember { mutableStateOf(0) }
        val reliabilityScope = rememberCoroutineScope()
        // reliabilityRevision is read to refresh scores after a run completes.
        val toolCapableInstalled = remember(state.selectedModel, storageRevision, reliabilityRevision) {
            ModelCatalog.all.filter { state.store.hasModel(it) && it.supportsTools }
        }
        val canBrowse = !state.modelImportRunning
        val canManage = enabled && !state.smokeTestRunning && !state.modelImportRunning
        val storedBytes = remember(state.selectedModel, storageRevision, state.modelImportRunning, state.modelDownloadRunning) {
            state.store.storedBytes(state.selectedModel)
        }
        LaunchedEffect(canBrowse, canManage) {
            if (!canBrowse) expanded = false
            if (!canManage) confirmingDelete = false
        }
        OutlinedButton(
            enabled = canBrowse,
            onClick = { phone = PhoneCheck.read(phoneContext); expanded = true },
            modifier = Modifier.fillMaxWidth().testTag("model_browse")
        ) { Text("Browse model families · ${com.battlesbudz.jarvis.v2.ai.ModelGuide.family(state.selectedModel)}") }
        Text(state.selectedModel.id, style = MaterialTheme.typography.titleSmall)
        ModelCompatibilityLabel(state.selectedModel)
        if (expanded && canBrowse) ModelBrowser(
            phone = phone, selectedId = state.selectedModel.id,
            isInstalled = { state.store.hasModel(it) },
            benchmarkStore = benchmarkStore,
            selectionEnabled = canManage,
            downloadingId = state.downloadingModelId,
            onDownload = state::download,
            onDismiss = { expanded = false },
            onSelect = state::select,
            reliabilityStore = reliabilityStore
        )
        Text(if (state.store.hasModel(state.selectedModel)) "Installed" else "Not installed")
        if (state.downloadingModelId != null) {
            Text("Downloading ${state.downloadingModelId}", style = MaterialTheme.typography.bodySmall)
            Text(state.downloadNotice, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (state.downloadTotalBytes > 0L) androidx.compose.material3.LinearProgressIndicator(
                progress = { (state.downloadBytes.toFloat() / state.downloadTotalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.TextButton(onClick = state::cancelDownload,
                modifier = Modifier.testTag("model_cancel_download")) { Text("Cancel download") }
        } else if (state.downloadNotice.isNotBlank()) Text(state.downloadNotice, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            enabled = !state.modelImportRunning,
            onClick = onOpenMemory,
            modifier = Modifier.fillMaxWidth().testTag("memory_open")
        ) { Text("Memory") }
        if (storedBytes > 0L) {
            OutlinedButton(enabled = canManage && !state.modelDownloadRunning, onClick = { confirmingDelete = true }) {
                Text("Delete model & cache · %.2f GB".format(java.util.Locale.US, storedBytes / 1_000_000_000.0))
            }
        }
        if (confirmingDelete && canManage) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete ${state.selectedModel.id}?") },
                text = { Text("Remove this model and its cache from Jarvis to free phone storage. You can download it again later. Any original file in Downloads stays there.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmingDelete = false
                        state.deleteSelectedModel()
                        storageRevision++
                    }) { Text("Delete") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                }
            )
        }
        if (state.selectedModel.requiresAccess) {
            Text("Publisher approval required. Accept the model terms in your browser, download its file, then use Import below.",
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.TextButton(onClick = {
                state.selectedModel.downloadUrl?.substringBefore("/resolve/")?.let { url ->
                    runCatching { uriHandler.openUri(url) }.onFailure { state.selectionError = "Could not open the publisher page." }
                }
            }) { Text("Open publisher page") }
        }
        Text(com.battlesbudz.jarvis.v2.ai.ModelGuide.quickUse(state.selectedModel), style = MaterialTheme.typography.bodyMedium)
        Text(com.battlesbudz.jarvis.v2.ai.ModelGuide.inputsLabel(state.selectedModel), style = MaterialTheme.typography.labelSmall)
        com.battlesbudz.jarvis.v2.ai.ModelGuide.visibleLimitation(state.selectedModel)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (!state.store.hasModel(state.selectedModel)) state.selectedModel.downloadBytes?.let {
            Text("Download: ${com.battlesbudz.jarvis.v2.ai.ModelGuidance.gb(it)}", style = MaterialTheme.typography.bodySmall)
        }
        com.battlesbudz.jarvis.v2.ai.ModelGuidance.storageNotice(state.selectedModel, phone, state.store.hasModel(state.selectedModel))?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.TextButton(onClick = { detailsOpen = true }, modifier = Modifier.testTag("selected_model_details")) {
            Text("Model details")
        }
        if (detailsOpen) ModelDetails(state.selectedModel, phone, benchmarkStore, state.store.hasModel(state.selectedModel), reliabilityStore) { detailsOpen = false }
        // Tool-call reliability check (M8 early enabler). User-triggered only:
        // never runs on launch. Scores persist per model in the reliability
        // store and surface in the model browser cards and details above.
        OutlinedButton(
            enabled = canManage && !state.modelDownloadRunning && !reliabilityRunning && toolCapableInstalled.isNotEmpty(),
            onClick = {
                reliabilityRunning = true
                reliabilityProgress = "Starting reliability check…"
                reliabilityScope.launch {
                    try {
                        withContext(Dispatchers.Default) {
                            val benchmark = ToolReliabilityBenchmark(
                                tryBeginModel = state.store::tryBeginModelSelection,
                                endModelOperation = state.store::endModelOperation,
                                engineFactory = { spec ->
                                    LiteRtLmEngine(
                                        spec.id,
                                        state.store.fileFor(spec).path,
                                        phoneContext.cacheDir.path,
                                        useGpu = spec.recommendedGpu,
                                        tools = MobileActionToolDefinitions.all()
                                    )
                                },
                                reportStore = reliabilityStore
                            )
                            val result = benchmark.runModels(toolCapableInstalled) { progress ->
                                withContext(Dispatchers.Main) {
                                    reliabilityProgress =
                                        "Checking ${progress.modelId}… ${progress.finishedFixtures}/${progress.totalFixtures} prompts " +
                                            "(model ${progress.finishedModels + 1} of ${progress.totalModels})"
                                }
                            }
                            withContext(Dispatchers.Main) {
                                reliabilityProgress = buildString {
                                    append("Done: ")
                                    append(result.reports.entries.joinToString { (_, report) ->
                                        "${report.modelId} ${report.percent.roundToInt()}%"
                                    })
                                    if (result.skipped.isNotEmpty()) {
                                        append(" · skipped (busy): ${result.skipped.joinToString()}")
                                    }
                                }
                                reliabilityRevision++
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            reliabilityProgress = "Reliability check failed: ${(e.message ?: "unknown error").take(120)}"
                        }
                    } finally {
                        withContext(Dispatchers.Main) { reliabilityRunning = false }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("tool_reliability_run")
        ) { Text(if (reliabilityRunning) "Running reliability check…" else "Run tool reliability check") }
        reliabilityProgress?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Runs 33 prompts per installed tool-capable model and scores tool-call accuracy. Takes several minutes; keep the app open.",
            style = MaterialTheme.typography.bodySmall
        )
        state.selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
