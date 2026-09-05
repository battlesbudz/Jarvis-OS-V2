package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections


private const val MAX_SAVED_DRAFT_CHARS = 16_000

@Composable
private fun JarvisChat(
    onSend: (String, Uri?, List<ChatEntry>, (String) -> Unit, (String) -> Unit) -> Unit,
    onCopyDiagnostics: (List<ChatEntry>) -> Unit,
    onMessagesChanged: (List<ChatEntry>) -> Unit,
    onSendingChanged: (Boolean) -> Unit,
    initialMessages: List<ChatEntry>
) {
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
                            Text(
                                message.text.ifBlank { "…" },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
                val updatedMessages = messages + ChatEntry("You", submitted) + ChatEntry("Jarvis", "")
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
