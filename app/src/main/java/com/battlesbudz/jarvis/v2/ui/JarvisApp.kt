package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.memory.AndroidMemoryOs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.lazy.items


@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun JarvisApp(
    store: ModelStore,
    conversationHistory: com.battlesbudz.jarvis.v2.chat.ConversationHistory,
    chatBusy: kotlinx.coroutines.flow.StateFlow<Boolean>,
    callState: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoiceSessionState>,
    onSendChat: (String, com.battlesbudz.jarvis.v2.chat.ChatAttachment?) -> String?,
    onSelectConversation: (String?) -> String?,
    onSelectModel: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> String?,
    onDeleteModel: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> String?,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    voiceModelStore: com.battlesbudz.jarvis.v2.voice.TtsModelStore,
    initialVoiceCalls: List<VoiceCallRecord>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onVoiceTurn: (Boolean, (String) -> Unit, (String, String, Boolean) -> Unit, (String) -> Unit) -> Unit,
    onWakeTest: ((String) -> Unit, () -> Unit) -> Unit,
    onStopWakeTest: () -> Unit,
    onEndVoiceCall: ((String) -> Unit) -> Unit,
    onResumeVoiceCall: (VoiceCallRecord, (String?) -> Unit) -> Unit,
    onDeleteVoiceCall: (String) -> Unit,
    onRefreshVoiceCalls: () -> List<VoiceCallRecord>,
    onDownloadGemma: ((Long, Long) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onExportSpeechAudio: () -> Unit,
) {
    var selectedModel by remember { mutableStateOf(store.selectedModel()) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    var pickerModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val gemmaReady = store.isUsable()
    var modelsReady by remember { mutableStateOf(store.isUsable()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf(
        if (store.smokeTestAttempted() && !store.smokeTestPassed())
            "The last model test did not pass or was interrupted. Retry the test or select another model."
        else "") }
    var smokeTestRunning by remember { mutableStateOf(false) }
    var modelImportRunning by remember { mutableStateOf(store.importInProgress()) }
    var modelDownloadRunning by remember { mutableStateOf(false) }
    var automaticSmokeTestAttempted by remember { mutableStateOf(false) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(-1L) }
    var setupElapsedSeconds by remember { mutableStateOf(0L) }
    var showingVoiceCalls by rememberSaveable { mutableStateOf(false) }
    var showingMemory by rememberSaveable { mutableStateOf(false) }
    var voiceCalls by remember { mutableStateOf(initialVoiceCalls) }
    var selectedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }
    var resumedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }
    val activeVoiceCall by com.battlesbudz.jarvis.v2.voice.VoiceSessionUi.armed.collectAsState()

    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val phoneContext = androidx.compose.ui.platform.LocalContext.current
    val memoryOs = remember(phoneContext.applicationContext) { AndroidMemoryOs.get(phoneContext.applicationContext) }
    var phone by remember { mutableStateOf(com.battlesbudz.jarvis.v2.ai.PhoneCheck.read(phoneContext)) }
    val modelSelector: @Composable (Boolean) -> Unit = { enabled ->
        Column {
            Text("AI model", style = MaterialTheme.typography.titleMedium)
            var expanded by remember { mutableStateOf(false) }
            var confirmingDelete by remember { mutableStateOf(false) }
            var detailsOpen by remember(selectedModel.id) { mutableStateOf(false) }
            var storageRevision by remember { mutableStateOf(0) }
            val canManage = enabled && !smokeTestRunning && !modelImportRunning && !modelDownloadRunning
            val storedBytes = remember(selectedModel, storageRevision, modelImportRunning, modelDownloadRunning) {
                store.storedBytes(selectedModel)
            }
            LaunchedEffect(canManage) {
                if (!canManage) { expanded = false; confirmingDelete = false }
            }
            OutlinedButton(
                enabled = canManage,
                onClick = { phone = com.battlesbudz.jarvis.v2.ai.PhoneCheck.read(phoneContext); expanded = true },
                modifier = Modifier.fillMaxWidth().testTag("model_browse")
            ) { Text("Browse model families · ${com.battlesbudz.jarvis.v2.ai.ModelGuide.family(selectedModel)}") }
            Text(selectedModel.id, style = MaterialTheme.typography.titleSmall)
            ModelCompatibilityLabel(selectedModel)
            if (expanded && canManage) ModelBrowser(
                phone = phone, selectedId = selectedModel.id,
                isInstalled = { store.hasModel(it) },
                onDismiss = { expanded = false },
                onSelect = { spec ->
                    selectionError = onSelectModel(spec)
                    if (selectionError == null) {
                        selectedModel = store.selectedModel()
                        modelsReady = store.isUsable()
                        smokeTestPassed = store.isUsable() && store.smokeTestPassed()
                        automaticSmokeTestAttempted = false
                        setupStatus = "Selected ${selectedModel.id}."
                    }
                    selectionError
                }
            )
            Text(if (store.hasModel(selectedModel)) "Installed" else "Not installed")
            OutlinedButton(
                enabled = !activeVoiceCall && !modelImportRunning && !modelDownloadRunning,
                onClick = { showingMemory = true },
                modifier = Modifier.fillMaxWidth().testTag("memory_open")
            ) { Text("Memory") }
            if (activeVoiceCall) Text("End the active call before managing memory.", style = MaterialTheme.typography.bodySmall)
            if (storedBytes > 0L) {
                OutlinedButton(enabled = canManage, onClick = { confirmingDelete = true }) {
                    Text("Delete model & cache · %.2f GB".format(java.util.Locale.US, storedBytes / 1_000_000_000.0))
                }
            }
            if (confirmingDelete && canManage) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmingDelete = false },
                    title = { Text("Delete ${selectedModel.id}?") },
                    text = { Text("Remove this model and its cache from Jarvis to free phone storage. You can download it again later. Any original file in Downloads stays there.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmingDelete = false
                            selectionError = onDeleteModel(selectedModel)
                            storageRevision++
                            modelsReady = store.isUsable()
                            smokeTestPassed = modelsReady && store.smokeTestPassed()
                            automaticSmokeTestAttempted = false
                            if (selectionError == null) setupStatus = "${selectedModel.id} deleted. Choose an installed model or download one."
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                    }
                )
            }
            if (selectedModel.requiresAccess) {
                Text("Publisher approval required. Accept the model terms in your browser, download its file, then use Import below.",
                    style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.TextButton(onClick = {
                    selectedModel.downloadUrl?.substringBefore("/resolve/")?.let { url ->
                        runCatching { uriHandler.openUri(url) }.onFailure { selectionError = "Could not open the publisher page." }
                    }
                }) { Text("Open publisher page") }
            }
            Text(com.battlesbudz.jarvis.v2.ai.ModelGuide.quickUse(selectedModel), style = MaterialTheme.typography.bodyMedium)
            Text(com.battlesbudz.jarvis.v2.ai.ModelGuide.inputsLabel(selectedModel), style = MaterialTheme.typography.labelSmall)
            com.battlesbudz.jarvis.v2.ai.ModelGuide.visibleLimitation(selectedModel)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (!store.hasModel(selectedModel)) selectedModel.downloadBytes?.let {
                Text("Download: ${com.battlesbudz.jarvis.v2.ai.ModelGuidance.gb(it)}", style = MaterialTheme.typography.bodySmall)
            }
            com.battlesbudz.jarvis.v2.ai.ModelGuidance.storageNotice(selectedModel, phone, store.hasModel(selectedModel))?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.TextButton(onClick = { detailsOpen = true }, modifier = Modifier.testTag("selected_model_details")) {
                Text("Model details")
            }
            if (detailsOpen) ModelDetails(selectedModel, phone) { detailsOpen = false }
            selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    val setupContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        androidx.work.WorkManager.getInstance(setupContext)
            .getWorkInfosForUniqueWorkFlow("jarvis-local-model-setup").collect { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                modelDownloadRunning = active != null
                if (active != null) {
                    active.progress.getString("stage")?.let { setupStatus = it }
                    downloadBytes = active.progress.getLong("downloaded", 0L)
                    downloadTotalBytes = active.progress.getLong("total", -1L)
                }
            }
    }

    LaunchedEffect(modelDownloadRunning) {
        if (!modelDownloadRunning) {
            setupElapsedSeconds = 0L
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            setupElapsedSeconds = (System.currentTimeMillis() - startedAt) / 1_000L
            delay(1_000L)
        }
    }

    // An import can finish after the previous Activity is destroyed during
    // rotation/fold changes. Keep the replacement screen synchronized with
    // the durable files even when the old callback was cancelled.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            modelsReady = store.isUsable()
            modelImportRunning = store.importInProgress()
            smokeTestPassed = modelsReady && store.smokeTestPassed()
        }
    }

    LaunchedEffect(modelsReady, smokeTestPassed, modelDownloadRunning, smokeTestRunning) {
        if (!modelsReady) automaticSmokeTestAttempted = false
        if (modelsReady && !smokeTestPassed && !modelDownloadRunning &&
            !smokeTestRunning && !automaticSmokeTestAttempted && !store.smokeTestAttempted()
        ) {
            automaticSmokeTestAttempted = true
            smokeTestRunning = true
            onRunModelSmokeTest { result ->
                smokeTestRunning = false
                setupStatus = result
                smokeTestPassed = store.isUsable() && store.smokeTestPassed()
            }
        }
    }

    val importModel: (Uri?, com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> Unit = { uri, spec ->
        if (uri != null) {
            modelImportRunning = true
            setupStatus = "Importing local model…"
            onImportModel(uri, spec) { result ->
                modelImportRunning = false
                setupStatus = result
                if (result == "Model imported successfully.") {
                    modelsReady = store.isUsable()
                    setupStatus = "${selectedModel.id} imported. Test it to start chatting."
                }
            }
        }
    }
    val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.resolve(pickerModelId))
    }

    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            if (showingMemory) {
                MemoryScreen(memoryOs = memoryOs, onBack = { showingMemory = false })
            } else if (modelsReady && smokeTestPassed) {
                when {
                    selectedVoiceCall != null -> {
                        val selected = requireNotNull(selectedVoiceCall)
                        VoiceCallDetailScreen(
                            call = selected,
                            onBack = { selectedVoiceCall = null },
                            onContinueChat = {
                                conversationHistory.openCall(selected)
                                onSelectConversation(conversationHistory.current.value.id)
                                selectedVoiceCall = null
                                showingVoiceCalls = false
                            },
                            onResume = { done ->
                                onResumeVoiceCall(selected) { error ->
                                    done(error)
                                    if (error == null) {
                                        resumedVoiceCall = selected
                                        selectedVoiceCall = null
                                        showingVoiceCalls = false
                                    }
                                }
                            }
                        )
                    }
                    showingVoiceCalls -> VoiceCallsScreen(
                        calls = voiceCalls,
                        onBack = { showingVoiceCalls = false },
                        onSelect = { selectedVoiceCall = it },
                        onDelete = {
                            onDeleteVoiceCall(it)
                            voiceCalls = onRefreshVoiceCalls()
                        }
                    )
                    else -> ConversationScreen(
                        history = conversationHistory, busy = chatBusy, callState = callState, onSend = onSendChat,
                        selectedModel = selectedModel,
                        onSelectConversation = onSelectConversation, onEndVoice = onEndVoiceCall,
                        onOpenVoiceCalls = {
                            voiceCalls = onRefreshVoiceCalls()
                            showingVoiceCalls = true
                        },
                        resumedVoice = resumedVoiceCall != null
                    ) { visible, settingsOpen, dismissSettings, returnToChat -> VoiceCallScreen(
                        visible = visible, settingsOpen = settingsOpen,
                        onDismissSettings = dismissSettings, onReturnToChat = returnToChat,
                        chatBusy = chatBusy, modelSelector = modelSelector,
                        resumedCall = resumedVoiceCall,
                        onResumeConsumed = { resumedVoiceCall = null },
                        voicePlayback = voicePlayback,
                        onVoiceTurn = onVoiceTurn,
                        onWakeTest = onWakeTest,
                        onStopWakeTest = onStopWakeTest,
                        onEndVoiceCall = onEndVoiceCall,
                        onCopyDiagnostics = onCopyDiagnostics,
                        onExportSpeechAudio = onExportSpeechAudio
                    ) }
                }
            } else {
                ModelSetup(
                    modelSelector = modelSelector,
                    ready = modelsReady,
                    gemmaReady = gemmaReady,
                    downloadAvailable = !selectedModel.requiresAccess,
                    testing = smokeTestRunning,
                    importing = modelImportRunning,
                    downloading = modelDownloadRunning,
                    downloadBytes = downloadBytes,
                    downloadTotalBytes = downloadTotalBytes,
                    status = setupStatus,
                    elapsedSeconds = setupElapsedSeconds,
                    onDownload = {
                        modelDownloadRunning = true
                        downloadBytes = 0L
                        downloadTotalBytes = -1L
                        setupStatus = "Preparing local Jarvis…"
                        onDownloadGemma({ downloaded, total ->
                            downloadBytes = downloaded
                            downloadTotalBytes = total
                        }, { status ->
                            setupStatus = status
                        }) { result ->
                            if (result.startsWith("Gemma found") || result.startsWith("Loading Gemma")) {
                                downloadBytes = 0L
                                downloadTotalBytes = -1L
                            }
                            modelDownloadRunning = false
                            setupStatus = result
                            modelsReady = store.isUsable()
                            smokeTestPassed = modelsReady && store.smokeTestPassed()
                        }
                    },
                    onPickGemma = { pickerModelId = selectedModel.id; gemmaPicker.launch(arrayOf("*/*")) },
                    onTest = {
                        smokeTestRunning = true
                        onRunModelSmokeTest.invoke { result ->
                            smokeTestRunning = false
                            setupStatus = result
                            if (store.isUsable() && store.smokeTestPassed()) {
                                smokeTestPassed = true
                            }
                        }
                    }
                )
            }
        }
    }
}
