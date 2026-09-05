package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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


private const val MAX_SAVED_DRAFT_CHARS = 16_000

@Composable
fun JarvisChat(
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(initialMessages) }
    var isSending by remember { mutableStateOf(false) }
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
            onClick = { onCopyDiagnostics(messages) },
            enabled = messages.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Copy diagnostics")
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
    initialMessages: List<ChatEntry>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onDownloadGemma: ((Long, Long) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var modelsReady by remember { mutableStateOf(store.isUsable()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
    var smokeTestRunning by remember { mutableStateOf(false) }
    var modelImportRunning by remember { mutableStateOf(store.importInProgress()) }
    var modelDownloadRunning by remember { mutableStateOf(false) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(-1L) }
    var setupElapsedSeconds by remember { mutableStateOf(0L) }

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

    val importModel: (Uri?, com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> Unit = { uri, spec ->
        if (uri != null) {
            modelImportRunning = true
            setupStatus = "Importing local model…"
            onImportModel(uri, spec) { result ->
                modelImportRunning = false
                setupStatus = result
                if (result == "Model imported successfully.") modelsReady = store.isUsable()
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
                JarvisChat(onSend, onCopyDiagnostics, onMessagesChanged, onSendingChanged, initialMessages)
            } else {
                ModelSetup(
                    ready = modelsReady,
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
private fun ModelSetup(
    ready: Boolean,
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
            "Jarvis runs privately on your phone. Setup looks for the exact Gemma model file in Downloads and imports it locally. Network model downloads are temporarily disabled.",
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing && !importing && !downloading
        ) {
            Text(if (downloading) "Checking Downloads…" else "Find and set up Jarvis")
        }
        if (downloading && downloadTotalBytes > 0L) {
            val progress = (downloadBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${(progress * 100).toInt()}% complete · ${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${elapsedSeconds}s elapsed",
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
            Text("Import an existing model file")
        }
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Testing Gemma 4 E2B…" else "Test Gemma 4 E2B")
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
    else -> "Preparing Jarvis"
}
package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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


private const val MAX_SAVED_DRAFT_CHARS = 16_000

@Composable
fun JarvisChat(
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf(initialMessages) }
    var isSending by remember { mutableStateOf(false) }
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
            onClick = { onCopyDiagnostics(messages) },
            enabled = messages.isNotEmpty() && !isSending,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Copy diagnostics")
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
    initialMessages: List<ChatEntry>,
    onRunModelSmokeTest: ((String) -> Unit) -> Unit,
    onDownloadGemma: ((Long, Long) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onImportModel: (Uri, com.battlesbudz.jarvis.v2.ai.LocalModelSpec, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var modelsReady by remember { mutableStateOf(store.isUsable()) }
    var smokeTestPassed by rememberSaveable { mutableStateOf(store.isUsable() && store.smokeTestPassed()) }
    var setupStatus by rememberSaveable { mutableStateOf("") }
    var smokeTestRunning by remember { mutableStateOf(false) }
    var modelImportRunning by remember { mutableStateOf(store.importInProgress()) }
    var modelDownloadRunning by remember { mutableStateOf(false) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(-1L) }
    var setupElapsedSeconds by remember { mutableStateOf(0L) }

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

    val importModel: (Uri?, com.battlesbudz.jarvis.v2.ai.LocalModelSpec) -> Unit = { uri, spec ->
        if (uri != null) {
            modelImportRunning = true
            setupStatus = "Importing local model…"
            onImportModel(uri, spec) { result ->
                modelImportRunning = false
                setupStatus = result
                if (result == "Model imported successfully.") modelsReady = store.isUsable()
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
                JarvisChat(onSend, onCopyDiagnostics, onMessagesChanged, onSendingChanged, initialMessages)
            } else {
                ModelSetup(
                    ready = modelsReady,
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
private fun ModelSetup(
    ready: Boolean,
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
            "Jarvis runs privately on your phone. The first setup downloads the verified local AI model once; future launches reuse it automatically.",
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing && !importing && !downloading
        ) {
            Text(if (downloading) "Setting up Jarvis…" else "Download and set up Jarvis")
        }
        if (downloading && downloadTotalBytes > 0L) {
            val progress = (downloadBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${(progress * 100).toInt()}% complete · ${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${elapsedSeconds}s elapsed",
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
            Text("Import an existing model file")
        }
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            Text(if (testing) "Testing Gemma 4 E2B…" else "Test Gemma 4 E2B")
        }
    }
}

private fun setupPhase(status: String): String = when {
    status.contains("app storage", ignoreCase = true) -> "Step 1 of 5: checking app storage"
    status.contains("Downloads", ignoreCase = true) || status.contains("exact filename", ignoreCase = true) ->
        "Step 2 of 5: checking Downloads"
    status.contains("Importing", ignoreCase = true) -> "Step 3 of 5: importing the existing model"
    status.contains("Downloading", ignoreCase = true) || status.contains("download", ignoreCase = true) ->
        "Step 3 of 5: downloading the model"
    status.contains("Verifying", ignoreCase = true) -> "Step 4 of 5: verifying the model"
    status.contains("Loading Gemma", ignoreCase = true) || status.contains("initializing", ignoreCase = true) ->
        "Step 5 of 5: initializing Gemma"
    else -> "Preparing Jarvis"
}
