package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.Date
import java.text.DateFormat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items


@Composable
fun JarvisApp(
    store: ModelStore,
    onSelectModel: (com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> String?,
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
    var modelsReady by remember { mutableStateOf(store.isUsable() && voiceModelStore.isReady()) }
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
    var voiceCalls by remember { mutableStateOf(initialVoiceCalls) }
    var selectedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }
    var resumedVoiceCall by remember { mutableStateOf<VoiceCallRecord?>(null) }

    val modelSelector: @Composable (Boolean) -> Unit = { enabled ->
        Column {
            Text("AI model", style = MaterialTheme.typography.titleMedium)
            ModelCatalog.all.forEach { spec ->
                OutlinedButton(
                    enabled = enabled && !smokeTestRunning && !modelImportRunning && !modelDownloadRunning,
                    onClick = {
                        selectionError = onSelectModel(spec)
                        if (selectionError == null) {
                            selectedModel = store.selectedModel()
                            modelsReady = store.isUsable() && voiceModelStore.isReady()
                            smokeTestPassed = modelsReady && store.smokeTestPassed()
                            automaticSmokeTestAttempted = false
                            setupStatus = "Selected ${selectedModel.id}."
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) {
                    Text(spec.id + if (selectedModel.id == spec.id) " · Selected"
                        else if (store.hasModel(spec)) " · Installed" else " · Not installed")
                }
            }
            Text("E4B is larger and may respond more slowly. You can switch back to E2B without downloading it again.",
                style = MaterialTheme.typography.bodySmall)
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
            modelsReady = store.isUsable() && voiceModelStore.isReady()
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
                    modelsReady = store.isUsable() && voiceModelStore.isReady()
                    if (!voiceModelStore.isReady()) {
                        setupStatus = "${selectedModel.id} imported. Install the local voice model to continue."
                    }
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
        Surface(modifier = Modifier.fillMaxSize()) {
            if (modelsReady && smokeTestPassed) {
                when {
                    selectedVoiceCall != null -> {
                        val selected = requireNotNull(selectedVoiceCall)
                        VoiceCallDetailScreen(
                            call = selected,
                            onBack = { selectedVoiceCall = null },
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
                    else -> VoiceCallScreen(
                        modelSelector = modelSelector,
                        resumedCall = resumedVoiceCall,
                        onResumeConsumed = { resumedVoiceCall = null },
                        voicePlayback = voicePlayback,
                        onVoiceTurn = onVoiceTurn,
                        onWakeTest = onWakeTest,
                        onStopWakeTest = onStopWakeTest,
                        onEndVoiceCall = onEndVoiceCall,
                        onOpenVoiceCalls = {
                            voiceCalls = onRefreshVoiceCalls()
                            showingVoiceCalls = true
                        },
                        onCopyDiagnostics = onCopyDiagnostics,
                        onExportSpeechAudio = onExportSpeechAudio
                    )
                }
            } else {
                ModelSetup(
                    modelSelector = modelSelector,
                    ready = modelsReady,
                    gemmaReady = gemmaReady,
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
                            modelsReady = store.isUsable() && voiceModelStore.isReady()
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

@Composable
private fun VoiceCallScreen(
    modelSelector: @Composable (Boolean) -> Unit,
    resumedCall: VoiceCallRecord?,
    onResumeConsumed: () -> Unit,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    onVoiceTurn: (Boolean, (String) -> Unit, (String, String, Boolean) -> Unit, (String) -> Unit) -> Unit,
    onWakeTest: ((String) -> Unit, () -> Unit) -> Unit,
    onStopWakeTest: () -> Unit,
    onEndVoiceCall: ((String) -> Unit) -> Unit,
    onOpenVoiceCalls: () -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onExportSpeechAudio: () -> Unit
) {
    val runtime = com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
    val runtimePhase by runtime.phase.collectAsState()
    val runtimeStatus by runtime.status.collectAsState()
    val runtimeArmed by runtime.armed.collectAsState()
    val microphonePaused by runtime.paused.collectAsState()
    val microphoneLevel by runtime.level.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var wakeTesting by remember { mutableStateOf(false) }
    var wakeTestStatus by remember { mutableStateOf("") }
    val wakeContext = androidx.compose.ui.platform.LocalContext.current
    fun startWakeTest() {
        wakeTesting = true
        wakeTestStatus = "Preparing wake test…"
        onWakeTest({ wakeTestStatus = it }, { wakeTesting = false })
    }
    val wakePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startWakeTest() else wakeTestStatus = "Microphone permission is required for the wake test."
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onStopWakeTest() }
    }
    var audioPathTesting by remember { mutableStateOf(false) }
    var inputTesting by remember { mutableStateOf(false) }
    val playback by voicePlayback.collectAsState()
    var callStarted by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var turnInFlight by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var turns by remember { mutableStateOf(resumedCall?.transcript.orEmpty().map { ChatEntry(it.role, it.text) }) }
    var provisionalUser by remember { mutableStateOf("") }
    fun requestVoiceTurn(start: Boolean) {
        listening = false
        status = "Preparing microphone…"
        turnInFlight = true
        onVoiceTurn(
            start,
            { update ->
                status = update
                if (update.startsWith("Waiting") || update.startsWith("Paused")) listening = false
                if (update.startsWith("Jarvis session stopped")) {
                    callStarted = false; listening = false; turnInFlight = false
                }
                if (update.startsWith("Processing your Voice Call") || update.startsWith("Preparing")) listening = false
                if (update.startsWith("Voice Call is listening")) listening = true
            },
            { role, text, complete ->
                if (role == "You" && !complete) {
                    provisionalUser = text
                } else {
                    if (role == "You") provisionalUser = ""
                turns = if (role == "Jarvis" && turns.lastOrNull()?.role == "Jarvis") {
                    turns.dropLast(1) + ChatEntry(role, if (complete) text else turns.last().text + text)
                } else {
                    turns + ChatEntry(role, text)
                }
                }
            },
            { result ->
                status = result
                turnInFlight = false
                provisionalUser = ""
                // A Voice Call is one continuous interaction. Once Jarvis has
                // finished the turn (including any tool action and speech),
                // immediately arm the next microphone turn. Explicit call end
                // sets callStarted=false, which prevents this re-arm.
                val failed = result.contains("could not start", ignoreCase = true) ||
                    result.contains("turn failed", ignoreCase = true) ||
                    result.contains("permission", ignoreCase = true)
                if (failed) {
                    callStarted = false
                }
                listening = false
                if (callStarted && !failed) turnInFlight = true
            }
        )
    }

    LaunchedEffect(Unit) {
        if (resumedCall != null) {
            callStarted = true
            requestVoiceTurn(start = true)
            onResumeConsumed()
        }
    }

    LaunchedEffect(runtimeArmed) {
        callStarted = runtimeArmed
        if (!runtimeArmed) { listening = false; turnInFlight = false }
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(
                onClick = onOpenVoiceCalls, enabled = !callStarted && !listening
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(com.battlesbudz.jarvis.v2.R.drawable.ic_voice_history),
                    contentDescription = "Voice calls", tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("JARVIS", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("Voice Call", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp))
            }
            androidx.compose.material3.IconButton(onClick = { settingsOpen = true }) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(com.battlesbudz.jarvis.v2.R.drawable.ic_voice_settings),
                    contentDescription = "Voice settings", tint = MaterialTheme.colorScheme.primary)
            }
        }
        val phase = if (runtimeArmed) runtimePhase.label else "Ready"
        val isListening = runtimeArmed && runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.LISTENING
        VoiceOrb(phase, if (runtimeArmed) {
            if (runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.SPEAKING) playback.level else microphoneLevel
        } else 0f)
        VoiceCaption(
            if (isListening) provisionalUser.trim().split(Regex("\\s+")).takeLast(32).joinToString(" ")
            else if (callStarted) playback.caption else "",
            if (isListening) "You" else "Jarvis"
        )
        if (!runtimeArmed) Button(
            onClick = { callStarted = true; requestVoiceTurn(start = true) },
            enabled = !turnInFlight && !wakeTesting && !inputTesting && !audioPathTesting,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("Start Jarvis session") }
        if (runtimeArmed) {
            if (runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.SPEAKING ||
                runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.THINKING) {
                Button(onClick = { runtime.controls.trySend(com.battlesbudz.jarvis.v2.voice.VoiceControl.STOP_REPLY) },
                    modifier = Modifier.fillMaxWidth()) { Text("Stop reply — listen to me") }
            }
            OutlinedButton(onClick = {
                runtime.controls.trySend(if (microphonePaused) com.battlesbudz.jarvis.v2.voice.VoiceControl.RESUME
                    else com.battlesbudz.jarvis.v2.voice.VoiceControl.PAUSE)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (microphonePaused) "Resume microphone" else "Pause microphone")
            }
            if (runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.LISTENING ||
                runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.THINKING ||
                runtimePhase == com.battlesbudz.jarvis.v2.voice.VoicePhase.SPEAKING) {
                TextButton(onClick = { runtime.controls.trySend(com.battlesbudz.jarvis.v2.voice.VoiceControl.END_CONVERSATION) }) {
                    Text("End conversation — keep Hey Jarvis on")
                }
            }
        }
        if (callStarted) {
            TextButton(
                onClick = {
                    // Prevent the completion callback from arming another
                    // microphone turn after the user explicitly ends the call.
                    callStarted = false
                    listening = false
                    turnInFlight = false
                    onEndVoiceCall { result ->
                        status = result
                    }
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Stop Jarvis session")
            }
        }
        val visibleStatus = if (runtimeArmed) runtimeStatus else status
        if (visibleStatus.isNotBlank() && !visibleStatus.startsWith("Voice Call turn complete")) {
            Text(visibleStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (settingsOpen) androidx.compose.material3.AlertDialog(
        onDismissRequest = { onStopWakeTest(); settingsOpen = false },
        title = { Text("Voice settings") },
        confirmButton = { TextButton(onClick = { onStopWakeTest(); settingsOpen = false }) { Text("Done") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Voice: Piper Northern English", style = MaterialTheme.typography.bodyMedium)
            modelSelector(!runtimeArmed && !callStarted && !turnInFlight && !wakeTesting && !audioPathTesting && !inputTesting)
        val assistantContext = androidx.compose.ui.platform.LocalContext.current
        VoiceInputSettings(enabled = !runtimeArmed && !callStarted && !turnInFlight && !wakeTesting && !audioPathTesting, onBusy = { inputTesting = it })
        var assistantSettingsMessage by remember { mutableStateOf(
            if (assistantContext.getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) "Jarvis is your default assistant."
            else "Choose Jarvis as your default assistant to enable hands-free app actions."
        ) }
        val assistantSettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {
            val roles = assistantContext.getSystemService(android.app.role.RoleManager::class.java)
            assistantSettingsMessage = if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
                "Jarvis is your default assistant."
            else "Select Jarvis under Digital assistant app in Android Settings."
        }
        TextButton(onClick = {
            val actions = listOf(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            var opened = false
            for (action in actions) {
                try {
                    assistantSettingsLauncher.launch(android.content.Intent(action))
                    opened = true
                    break
                } catch (_: android.content.ActivityNotFoundException) {
                    // Some manufacturers expose only the default-apps screen.
                } catch (_: SecurityException) {
                    // Try the public default-apps fallback.
                }
            }
            assistantSettingsMessage = if (opened)
                "Choose Digital assistant app, then Jarvis."
            else "Open Android Settings → Apps → Default apps → Digital assistant app → Jarvis."
        }) { Text("Default assistant settings") }
        if (assistantSettingsMessage.isNotBlank()) {
            Text(assistantSettingsMessage, style = MaterialTheme.typography.bodySmall)
        }
        Text("Automatic microphone handoff", style = MaterialTheme.typography.bodyMedium)
        Text("Jarvis releases its microphone for other recordings and automatically resumes your call or wake listening afterward.",
            style = MaterialTheme.typography.bodySmall)
        val helperConnected by com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff.keyboardHelperConnected.collectAsState()
        Text(if (helperConnected) "Keyboard microphone-button handoff is enabled."
            else "Enable keyboard handoff to give dictation priority when you tap its microphone.",
            style = MaterialTheme.typography.bodySmall)
        Text("Checks only the tapped keyboard control’s label or ID. Ordinary typing and leaving a keyboard open do not pause Jarvis.",
            style = MaterialTheme.typography.bodySmall)
        var keyboardSetupError by remember { mutableStateOf("") }
        TextButton(onClick = {
            try { wakeContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { keyboardSetupError = "Open Android Settings → Accessibility → Installed apps → Jarvis keyboard microphone handoff." }
        }) { Text(if (helperConnected) "Keyboard handoff settings" else "Enable keyboard microphone handoff") }
        if (keyboardSetupError.isNotBlank()) Text(keyboardSetupError, style = MaterialTheme.typography.bodySmall)
        var diagnosticsOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }, enabled = !wakeTesting && !audioPathTesting) {
            Text(if (diagnosticsOpen) "Hide development diagnostics" else "Development diagnostics")
        }
        if (diagnosticsOpen) {
            AudioPathDiagnosticCard(enabled = !runtimeArmed && !wakeTesting && !turnInFlight && !inputTesting,
                onBusyChanged = { audioPathTesting = it })
            TextButton(onClick = {
                if (wakeTesting) onStopWakeTest()
                else if (wakeContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startWakeTest()
                else wakePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }, enabled = !callStarted && !turnInFlight && !audioPathTesting && !inputTesting) {
                Text(if (wakeTesting) "Stop wake test" else "Test wake word")
            }
            if (wakeTestStatus.isNotBlank()) Text(wakeTestStatus, style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { onCopyDiagnostics(turns + if (provisionalUser.isNotBlank()) listOf(ChatEntry("You", provisionalUser)) else emptyList()) },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Copy diagnostics")
            }
            TextButton(onClick = onExportSpeechAudio) { Text("Save latest reply audio") }
        }
        } }
    )
}

@Composable
private fun VoiceCallsScreen(
    calls: List<VoiceCallRecord>,
    onBack: () -> Unit,
    onSelect: (VoiceCallRecord) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Voice Calls", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (calls.isEmpty()) {
            Text(
                "Completed Voice Calls will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(calls.sortedByDescending { it.startedAtMs }, key = { it.id }) { call ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(call.title ?: "Untitled Voice Call")
                                Text(
                                    DateFormat.getDateTimeInstance(
                                        DateFormat.MEDIUM,
                                        DateFormat.SHORT
                                    ).format(Date(call.startedAtMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onSelect(call) }) { Text("Open") }
                            TextButton(onClick = { onDelete(call.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallDetailScreen(
    call: VoiceCallRecord,
    onBack: () -> Unit,
    onResume: ((String?) -> Unit) -> Unit
) {
    var resuming by remember(call.id) { mutableStateOf(false) }
    var resumeError by remember(call.id) { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(call.title ?: "Voice Call", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack, enabled = !resuming) { Text("Back") }
        }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(call.startedAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            call.transcript.forEach { entry ->
                val label = if (entry.role == "Jarvis" && entry.delivery != null &&
                    entry.delivery.state != com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED) "Jarvis (generated)" else entry.role
                Text(
                    "$label: ${entry.text}",
                    color = if (entry.role == "You") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.role == "Jarvis") {
                    entry.delivery?.let { delivery ->
                        if (delivery.state != com.battlesbudz.jarvis.v2.voice.SpeechDeliveryState.COMPLETED) {
                            Text("Playback ${delivery.state.name.lowercase()}. Completed spoken text: " +
                                delivery.deliveredText.ifBlank { "None" } +
                                if (delivery.partialSpanIndex != null) " · Last segment partly played." else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    entry.actions.forEach { action -> Text("Action result: ${action.message}", style = MaterialTheme.typography.bodySmall) }
                    entry.latency?.let { TurnLatencyFooter(it) }
                }
            }
            call.taskStatus?.let { task ->
                Text("Task status: ${task.state}", style = MaterialTheme.typography.labelLarge)
                task.completedSteps.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                task.pendingSteps.forEach { Text("○ $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        resumeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (!resuming) {
                resuming = true
                resumeError = null
                onResume { error ->
                    // Keep a successful button latched until this screen leaves composition.
                    if (error != null) resuming = false
                    resumeError = error
                }
            }
        }, enabled = !resuming, modifier = Modifier.fillMaxWidth()) {
            Text(if (resuming) "Preparing call…" else "Resume conversation")
        }
    }
}

@Composable
private fun ModelSetup(
    modelSelector: @Composable (Boolean) -> Unit,
    ready: Boolean,
    gemmaReady: Boolean,
    testing: Boolean,
    importing: Boolean,
    downloading: Boolean,
    downloadBytes: Long,
    downloadTotalBytes: Long,
    status: String,
    elapsedSeconds: Long,
    onDownload: () -> Unit,
    onPickGemma: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Jarvis setup", style = MaterialTheme.typography.headlineMedium)
        modelSelector(!testing && !importing && !downloading)
        Text(
            if (gemmaReady) {
                "The selected Gemma model is ready. Install the local Piper voice model to enable Jarvis speaking."
            } else {
                "Jarvis runs privately on your phone. Install Gemma and the local Piper voice model, or choose a compatible file for the selected model."
            },
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing && !importing && !downloading
        ) {
            Text(
                when {
                    downloading -> "Downloading and installing…"
                    gemmaReady -> "Install voice model"
                    else -> "Download and install Jarvis"
                }
            )
        }
        if (downloading && downloadTotalBytes > 0L) {
            val progress = (downloadBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${formatMegabytes(downloadBytes)} / ${formatMegabytes(downloadTotalBytes)} MB " +
                    "(${(progress * 100).toInt()}%) · ${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · " +
                    (if (downloadBytes > 0L) "${formatMegabytes(downloadBytes)} MB processed · " else "") +
                    "${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (status.isNotBlank() && downloading) {
            Text(status, modifier = Modifier.padding(top = 20.dp))
        } else if (status.isNotBlank()) {
            Text(status, modifier = Modifier.padding(top = 20.dp))
        }
        OutlinedButton(
            onClick = onPickGemma,
            enabled = !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text("Already downloaded — choose the model")
        }
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Checking selected model…" else "Check selected model")
        }
    }
}

private fun setupPhase(status: String): String = when {
    status.contains("app storage", ignoreCase = true) -> "Step 1 of 5: checking app storage"
    status.contains("Downloads", ignoreCase = true) || status.contains("exact filename", ignoreCase = true) ->
        "Step 2 of 5: checking Downloads"
    status.contains("Importing", ignoreCase = true) -> "Step 3 of 5: importing the existing model"
    status.contains("Verifying", ignoreCase = true) -> "Step 4 of 5: verifying the model"
    status.contains("Loading Gemma", ignoreCase = true) || status.contains("initializing", ignoreCase = true) ->
        "Step 5 of 5: initializing Gemma"
    status.contains("Downloading the local Jarvis voice model", ignoreCase = true) ->
        "Step 2 of 5: downloading Piper"
    status.contains("Installing Piper", ignoreCase = true) || status.contains("Piper unpack", ignoreCase = true) ->
        "Step 3 of 5: unpacking Piper"
    else -> "Preparing Jarvis"
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes < 0L) "—" else String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
