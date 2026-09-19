package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ConversationScreen(
    history: ConversationHistory,
    busy: StateFlow<Boolean>,
    onSend: (String) -> String?,
    onSelectConversation: (String?) -> String?,
    onEndVoice: ((String) -> Unit) -> Unit,
    modelSelector: @Composable (Boolean) -> Unit,
    resumedVoice: Boolean,
    voiceContent: @Composable () -> Unit
) {
    val thread by history.current.collectAsState()
    val sending by busy.collectAsState()
    val armed by VoiceSessionUi.armed.collectAsState()
    var mode by rememberSaveable { mutableStateOf("Chat") }
    var wasArmed by remember { mutableStateOf(armed) }
    var settings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(thread.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(resumedVoice) { if (resumedVoice) mode = "Voice" }
    LaunchedEffect(armed) {
        if (armed) mode = "Voice"
        else if (wasArmed) mode = "Chat"
        wasArmed = armed
    }
    LaunchedEffect(thread.id, thread.messages.lastOrNull()?.text) {
        if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TabRow(selectedTabIndex = if (mode == "Chat") 0 else 1) {
            Tab(selected = mode == "Chat", onClick = {
                if (armed) onEndVoice { }
                mode = "Chat"
            }, text = { Text("Chat") })
            Tab(selected = mode == "Voice", enabled = !sending, onClick = { mode = "Voice" }, text = { Text("Voice") })
        }
        if (mode == "Voice") Box(Modifier.weight(1f)) { voiceContent() }
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showHistory = true }, enabled = !sending && !armed) { Text("Conversations") }
                TextButton(onClick = { settings = true }) { Text("AI model") }
                TextButton(onClick = { error = onSelectConversation(null) }, enabled = !sending && !armed) { Text("New") }
            }
            if (thread.messages.isEmpty()) Text("Type a message or switch to Voice. Both stay in this conversation.",
                modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(thread.messages, key = { it.id }) { message ->
                    Surface(color = if (message.role == "You") MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(message.role + if (message.spoken) " · Spoken transcript" else "",
                                style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(message.text.ifBlank { if (sending) "Thinking…" else "No reply was saved." },
                                    fontStyle = if (message.spoken) FontStyle.Italic else FontStyle.Normal,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            if (!message.complete && message.role == "Jarvis" && message.text.isNotBlank() && !sending)
                                Text("Reply interrupted or not fully spoken", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message Jarvis") },
                    modifier = Modifier.weight(1f), maxLines = 5, enabled = !armed)
                Button(enabled = draft.isNotBlank() && !sending && !armed, onClick = {
                    error = onSend(draft)
                    if (error == null) draft = ""
                }) { Text(if (sending) "Thinking…" else "Send") }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Choose your AI") },
        text = { modelSelector(!sending && !armed) },
        confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Conversations") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(history.list(), key = { it.id }) { saved ->
                TextButton(onClick = { error = onSelectConversation(saved.id); if (error == null) showHistory = false }) {
                    Text(saved.title)
                }
            }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } })
}
