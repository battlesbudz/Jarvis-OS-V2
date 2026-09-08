package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import com.battlesbudz.jarvis.v2.voice.VoiceCallRecord
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.chat.AssistantStreamFilter
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor
import com.battlesbudz.jarvis.v2.actions.MobileActionPipeline
import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import java.util.Date
import java.text.DateFormat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items


private const val MAX_SAVED_DRAFT_CHARS = 16_000

@Composable
fun JarvisChat(
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onRunDirectAudioTest: ((String) -> Unit, (String) -> Unit) -> Unit,
    onRunDirectAudioToolTest: ((String) -> Unit, (String) -> Unit) -> Unit,
    onVoiceTurn: (Boolean, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(initialMessages) }
    var isSending by remember { mutableStateOf(false) }
    var directAudioTestRunning by remember { mutableStateOf(false) }
    var directAudioTestStatus by rememberSaveable { mutableStateOf("") }
    var directAudioToolTestRunning by remember { mutableStateOf(false) }
    var directAudioToolTestStatus by rememberSaveable { mutableStateOf("") }
    var voiceTurnActive by remember { mutableStateOf(false) }
    var voiceTurnStatus by rememberSaveable { mutableStateOf("") }
    var attachedImageName by rememberSaveable { mutableStateOf<String?>(null) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    val transcriptScrollState = rememberScrollState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachedImageUri = uri
        attachedImageName = uri?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: if (uri != null) "selected image" else null
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (messages.isNotEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(transcriptScrollState).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                messages.forEach { message ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = if (message.role == "You") {
                            androidx.compose.ui.Alignment.End
                        } else {
                            androidx.compose.ui.Alignment.Start
                        }
                    ) {
                        Text(
                            message.role,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (message.role == "You") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        Surface(
                            color = if (message.role == "You") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                message.imageUri?.let { imageUri ->
                                    val bitmap by produceState<Bitmap?>(
                                        initialValue = null,
                                        key1 = imageUri
                                    ) {
                                        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                openTranscriptImageStream(context, Uri.parse(imageUri))
                                                    ?.use { BitmapFactory.decodeStream(it) }
                                            }.getOrNull()
                                        }
                                    }
                                    bitmap?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = "Attached image",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 280.dp)
                                                .padding(bottom = 8.dp)
                                        )
                                    }
                                }
                                Text(
                                    message.text.ifBlank { "…" },
                                    color = if (message.role == "You") {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        if (attachedImageName != null) {
            Text(
                "Attached: $attachedImageName",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.Bottom
        ) {
            Button(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isSending,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("📎")
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(MAX_SAVED_DRAFT_CHARS) },
                label = { Text("Message Jarvis") },
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val start = !voiceTurnActive
                voiceTurnActive = start
                onVoiceTurn(
                    start,
                    { status -> voiceTurnStatus = status },
                    { result ->
                        voiceTurnStatus = result
                        voiceTurnActive = false
                    }
                )
            },
            enabled = !isSending && !directAudioTestRunning && !directAudioToolTestRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(if (voiceTurnActive) "Stop and send voice turn" else "Start Voice Call turn")
        }
        if (voiceTurnStatus.isNotBlank()) {
            Text(
                voiceTurnStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Button(
            onClick = { onCopyDiagnostics(messages) },
            enabled = messages.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Copy diagnostics")
        }
        Button(
            onClick = {
                directAudioTestRunning = true
                directAudioTestStatus = "Requesting microphone…"
                onRunDirectAudioTest(
                    { status -> directAudioTestStatus = status },
                    { result ->
                        directAudioTestStatus = result
                        directAudioTestRunning = false
                    }
                )
            },
            enabled = !isSending && !directAudioTestRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(if (directAudioTestRunning) "Recording / testing E2B audio…" else "Test 25-second E2B audio")
        }
        if (directAudioTestStatus.isNotBlank()) {
            Text(
                directAudioTestStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Button(
            onClick = {
                directAudioToolTestRunning = true
                directAudioToolTestStatus = "Requesting microphone… Say: battery, volume, or open an app."
                onRunDirectAudioToolTest(
                    { status -> directAudioToolTestStatus = status },
                    { result ->
                        directAudioToolTestStatus = result
                        directAudioToolTestRunning = false
                    }
                )
            },
            enabled = !isSending && !directAudioTestRunning && !directAudioToolTestRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(if (directAudioToolTestRunning) "Testing E2B voice tool…" else "Test E2B voice tool call")
        }
        if (directAudioToolTestStatus.isNotBlank()) {
            Text(
                directAudioToolTestStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Button(
            onClick = {
                val selectedImageUri = attachedImageUri
                val submitted = buildString {
                    append(prompt.trim())
                    attachedImageName?.let {
                        append("\n\n[Attached image: $it]")
                    }
                }.trim()
                prompt = ""
                attachedImageUri = null
                attachedImageName = null
                isSending = true
                val updatedMessages = messages +
                    ChatEntry("You", submitted, selectedImageUri?.toString()) +
                    ChatEntry("Jarvis", "")
                messages = updatedMessages
                onMessagesChanged(updatedMessages)
                onSendingChanged(true)
                onSend(
                    submitted,
                    selectedImageUri,
                    messages.dropLast(2),
                    { token ->
                        messages = messages.dropLast(1) +
                            ChatEntry("Jarvis", messages.lastOrNull()?.text.orEmpty() + token)
                    },
                    { result ->
                        messages = messages.dropLast(1) + ChatEntry("Jarvis", result)
                        onMessagesChanged(messages)
                        onSendingChanged(false)
                        isSending = false
                    }
                )
            },
            enabled = prompt.isNotBlank() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(if (isSending) "Thinking…" else "Send")
        }
    }
}

private fun openTranscriptImageStream(
    context: android.content.Context,
    uri: Uri
): InputStream? {
    // Transcript rendering is independent of the raw bytes sent to Gemma.
    return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
        }.getOrNull()
}

@Composable
fun JarvisApp(
    store: ModelStore,
    ttsComparisonStore: com.battlesbudz.jarvis.v2.voice.TtsComparisonStore,
    onSelectTts: (com.battlesbudz.jarvis.v2.voice.TtsEngine) -> Boolean,
    onTtsBenchmark: (com.battlesbudz.jarvis.v2.voice.TtsEngine?, (String) -> Unit, () -> Unit) -> Unit,
    onStopTtsBenchmark: () -> Unit,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    voiceModelStore: com.battlesbudz.jarvis.v2.voice.KokoroModelStore,
    initialMessages: List<ChatEntry>,
    initialVoiceCalls: List<VoiceCallRecord>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onRunDirectAudioTest: ((String) -> Unit, (String) -> Unit) -> Unit,
    onRunDirectAudioToolTest: ((String) -> Unit, (String) -> Unit) -> Unit,
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
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
) {
    val gemmaReady = store.isUsable()
    var modelsReady by remember { mutableStateOf(store.isUsable() && voiceModelStore.isReady()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
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
            !smokeTestRunning && !automaticSmokeTestAttempted
        ) {
            automaticSmokeTestAttempted = true
            smokeTestRunning = true
            onRunModelSmokeTest { result ->
                smokeTestRunning = false
                setupStatus = result
                smokeTestPassed = result == "Gemma 4 E2B initialized successfully."
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
                        setupStatus = "Gemma E2B imported. Install the local voice model to continue."
                    }
                }
            }
        }
    }
    val gemmaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importModel(uri, ModelCatalog.gemma4E2b)
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
                        resumedCall = resumedVoiceCall,
                        onResumeConsumed = { resumedVoiceCall = null },
                        ttsComparisonStore = ttsComparisonStore,
                        onSelectTts = onSelectTts,
                        onTtsBenchmark = onTtsBenchmark,
                        onStopTtsBenchmark = onStopTtsBenchmark,
                        voicePlayback = voicePlayback,
                        onVoiceTurn = onVoiceTurn,
                        onWakeTest = onWakeTest,
                        onStopWakeTest = onStopWakeTest,
                        onEndVoiceCall = onEndVoiceCall,
                        onOpenVoiceCalls = {
                            voiceCalls = onRefreshVoiceCalls()
                            showingVoiceCalls = true
                        },
                        onCopyDiagnostics = onCopyDiagnostics
                    )
                }
            } else {
                ModelSetup(
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
                    onPickGemma = { gemmaPicker.launch(arrayOf("*/*")) },
                    onTest = {
                        smokeTestRunning = true
                        onRunModelSmokeTest.invoke { result ->
                            smokeTestRunning = false
                            setupStatus = result
                            if (result == "Gemma 4 E2B initialized successfully.") {
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
    resumedCall: VoiceCallRecord?,
    onResumeConsumed: () -> Unit,
    ttsComparisonStore: com.battlesbudz.jarvis.v2.voice.TtsComparisonStore,
    onSelectTts: (com.battlesbudz.jarvis.v2.voice.TtsEngine) -> Boolean,
    onTtsBenchmark: (com.battlesbudz.jarvis.v2.voice.TtsEngine?, (String) -> Unit, () -> Unit) -> Unit,
    onStopTtsBenchmark: () -> Unit,
    voicePlayback: kotlinx.coroutines.flow.StateFlow<com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame>,
    onVoiceTurn: (Boolean, (String) -> Unit, (String, String, Boolean) -> Unit, (String) -> Unit) -> Unit,
    onWakeTest: ((String) -> Unit, () -> Unit) -> Unit,
    onStopWakeTest: () -> Unit,
    onEndVoiceCall: ((String) -> Unit) -> Unit,
    onOpenVoiceCalls: () -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit
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
    var ttsSettingsOpen by remember { mutableStateOf(false) }
    var selectedTts by remember { mutableStateOf(ttsComparisonStore.selectedEngine()) }
    val playback by voicePlayback.collectAsState()
    var callStarted by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var turnInFlight by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var turns by remember { mutableStateOf(resumedCall?.transcript.orEmpty().map { ChatEntry(it.role, it.text) }) }
    var provisionalUser by remember { mutableStateOf("") }
    if (ttsSettingsOpen) TtsComparisonDialog(
        store = ttsComparisonStore, canChange = !callStarted && !turnInFlight && !wakeTesting,
        onSelect = { engine -> onSelectTts(engine).also { if (it) selectedTts = engine } },
        onBenchmark = onTtsBenchmark, onStop = onStopTtsBenchmark,
        onDismiss = { ttsSettingsOpen = false }
    )
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
            enabled = !turnInFlight && !wakeTesting,
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
        onDismissRequest = { settingsOpen = false },
        title = { Text("Voice settings") },
        confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { ttsSettingsOpen = true }, enabled = !wakeTesting) { Text("Voice: ${selectedTts.label}") }
        val assistantContext = androidx.compose.ui.platform.LocalContext.current
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
        Text("Jarvis pauses for another app’s recording and resumes the previous listening mode afterward. If a keyboard refuses microphone access, use Pause mic in the Jarvis notification, then Resume mic when finished.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            if (wakeTesting) onStopWakeTest()
            else if (wakeContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startWakeTest()
            else wakePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }, enabled = !callStarted && !turnInFlight) {
            Text(if (wakeTesting) "Stop wake test" else "Test wake word")
        }
        if (wakeTestStatus.isNotBlank()) Text(wakeTestStatus, style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { onCopyDiagnostics(turns + if (provisionalUser.isNotBlank()) listOf(ChatEntry("You", provisionalUser)) else emptyList()) },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Copy diagnostics")
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
                Text(
                    "${entry.role}: ${entry.text}",
                    color = if (entry.role == "You") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        Text(
            if (gemmaReady) {
                "Gemma E2B is ready. Install the local Kokoro voice model to enable Jarvis speaking."
            } else {
                "Jarvis runs privately on your phone. Install Gemma and the local Kokoro voice model, or choose your own compatible E2B file."
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
            Text(if (testing) "Preparing Gemma 4 E2B…" else "Test Gemma 4 E2B")
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
        "Step 2 of 5: downloading Kokoro"
    status.contains("Installing Kokoro", ignoreCase = true) || status.contains("Kokoro unpack", ignoreCase = true) ->
        "Step 3 of 5: unpacking Kokoro"
    else -> "Preparing Jarvis"
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes < 0L) "—" else String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
