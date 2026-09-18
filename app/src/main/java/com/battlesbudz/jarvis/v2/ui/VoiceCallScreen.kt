package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items


@Composable
internal fun VoiceCallScreen(
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
            CallEvidenceExport(enabled = !runtimeArmed && !turnInFlight)
            LiveComparisonCard(enabled = !runtimeArmed && !wakeTesting && !turnInFlight && !inputTesting && !audioPathTesting)
            AudioPathDiagnosticCard(enabled = !runtimeArmed && !wakeTesting && !turnInFlight && !inputTesting && !audioPathTesting,
                onBusyChanged = { audioPathTesting = it })
            DuplexEchoDiagnosticCard(enabled = !runtimeArmed && !wakeTesting && !turnInFlight && !inputTesting && !audioPathTesting,
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

