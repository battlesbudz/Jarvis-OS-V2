package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.voice.VoiceNavigationPolicy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.chat.*
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    history: ConversationHistory,
    busy: StateFlow<Boolean>,
    callState: StateFlow<VoiceSessionState>,
    onSend: (String, ChatAttachment?) -> String?,
    selectedModel: LocalModelSpec,
    onSelectConversation: (String?) -> String?,
    onEndVoice: ((String) -> Unit) -> Unit,
    onOpenVoiceCalls: () -> Unit,
    resumedVoice: Boolean,
    onOpenMemory: () -> Unit = {},
    forceVoiceDestination: Boolean = false,
    onForceVoiceConsumed: () -> Unit = {},
    forceChatDestination: Boolean = false,
    onForceChatConsumed: () -> Unit = {},
    voiceContent: @Composable (visible: Boolean, settingsOpen: Boolean, dismissSettings: () -> Unit, returnToChat: () -> Unit) -> Unit
) {
    val thread by history.current.collectAsState()
    val sending by busy.collectAsState()
    val armed by VoiceSessionUi.armed.collectAsState()
    val voiceStatus by VoiceSessionUi.status.collectAsState()
    val voiceState by callState.collectAsState()
    var hadCall by remember { mutableStateOf(false) }
    var voiceVisible by rememberSaveable { mutableStateOf(false) }
    var wasArmed by remember { mutableStateOf(armed) }
    var settings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(thread.id) { mutableStateOf("") }
    var pendingUri by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable(thread.id) { mutableStateOf(AttachmentKind.IMAGE) }
    var preparingAttachment by remember { mutableStateOf(false) }
    val pendingAttachment = pendingUri?.let { ChatAttachment(it, pendingKind) }
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(thread.id) {
        onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true)
            pendingUri?.let { ChatMediaStore.discard(context, ChatAttachment(it, pendingKind)) } }
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun returnToChat() {
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_CHAT) { onEndVoice {} }
        voiceVisible = false
    }
    BackHandler(enabled = voiceVisible && !settings && !showHistory) { returnToChat() }
    LaunchedEffect(voiceState) {
        if (voiceState != VoiceSessionState.PASSIVE_LISTENING) hadCall = true
        else if (hadCall) {
            hadCall = false
            returnToChat()
        }
    }
    LaunchedEffect(resumedVoice) { if (resumedVoice) voiceVisible = true }
    LaunchedEffect(forceVoiceDestination) {
        if (forceVoiceDestination) {
            VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { onEndVoice {} }
            voiceVisible = true; onForceVoiceConsumed()
        }
    }
    LaunchedEffect(forceChatDestination) {
        if (forceChatDestination) {
            returnToChat()
            onForceChatConsumed()
        }
    }
    LaunchedEffect(armed) {
        if (armed) voiceVisible = true
        else if (wasArmed) voiceVisible = false
        wasArmed = armed
    }
    LaunchedEffect(voiceVisible) {
        if (voiceVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(thread.id, thread.messages.lastOrNull()?.text) {
        if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("JARVIS", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(enabled = !preparingAttachment, onClick = { settings = true }) { Text("Settings") }
        }
        if (armed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (voiceStatus.isBlank()) "Voice call active" else "Voice call active · $voiceStatus",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("voice_call_status")
                )
                TextButton(onClick = {
                    VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.EXPLICIT_END) {
                        onEndVoice { result -> if (result.isNotBlank()) error = result }
                    }
                    voiceVisible = false
                }, modifier = Modifier.testTag("voice_call_end")) { Text("End call") }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedButton(selected = !voiceVisible, onClick = { returnToChat() },
                modifier = Modifier.testTag("chat_tab"),
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Chat") }
            SegmentedButton(selected = voiceVisible, enabled = !sending && !preparingAttachment, onClick = {
                VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { onEndVoice {} }
                voiceVisible = true
            },
                modifier = Modifier.testTag("voice_tab"),
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Voice call") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showHistory = true }, enabled = !sending && !armed && !preparingAttachment) { Text("Conversations") }
            TextButton(onClick = { error = onSelectConversation(null) }, enabled = !sending && !armed && !preparingAttachment) { Text("New") }
        }
        // The conversation stays mounted beneath the voice surface: same draft, list and thread.
        // Hidden transcript nodes must not remain readable by accessibility services during a call.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().alpha(if (voiceVisible) 0f else 1f)
                .then(if (voiceVisible) Modifier.clearAndSetSemantics { } else Modifier)) {
                if (thread.messages.isEmpty()) Text("Type a message or switch to Voice call. It's all one conversation.",
                    modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(thread.messages, key = { it.id }) { message ->
                        Surface(color = if (message.role == "You") MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(message.role + if (message.spoken) " · Spoken transcript" else "",
                                    style = MaterialTheme.typography.labelMedium)
                                message.attachment?.let { ChatAttachmentPreview(it) }
                                SelectionContainer {
                                    Text(message.text.ifBlank { if (sending) "Thinking…" else "No reply was saved." },
                                        fontStyle = if (message.spoken) FontStyle.Italic else FontStyle.Normal,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                                if (message.role == "Jarvis")
                                    Text((message.metrics ?: com.battlesbudz.jarvis.v2.diagnostics.ReplyMetrics.unavailable).summary(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("reply_metrics_${message.id}"))
                                if (!message.complete && message.role == "Jarvis" && message.text.isNotBlank() && !sending)
                                    Text("Reply interrupted or not fully spoken", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                pendingAttachment?.let { attached ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (attached.kind == AttachmentKind.IMAGE) "Image attached" else "Audio clip attached", modifier = Modifier.weight(1f))
                        TextButton(enabled = !sending && !preparingAttachment, onClick = {
                            ChatMediaStore.discard(context, attached); pendingUri = null
                        }) { Text("Remove") }
                    }
                    if (!AttachmentPolicy.accepts(selectedModel, attached.kind))
                        Text("Choose a compatible model or remove the attachment.", color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (preparingAttachment) Text("Preparing attachment…", modifier = Modifier.padding(horizontal = 16.dp))
                if (armed) Text("Attachments are unavailable during a voice call. End the call to add one.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                else ChatAttachmentPicker(selectedModel, enabled = !sending && !voiceVisible && !preparingAttachment,
                    onBusy = { preparingAttachment = it }, onError = { error = it }, onPrepared = { attached ->
                        pendingAttachment?.let { ChatMediaStore.discard(context, it) }
                        pendingKind = attached.kind; pendingUri = attached.uri; error = null
                    })
                Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message Jarvis") },
                        modifier = Modifier.weight(1f).testTag("chat_composer"), maxLines = 5, enabled = !voiceVisible)
                    val canSend = if (armed) draft.isNotBlank() && pendingAttachment == null
                    else (draft.isNotBlank() || pendingAttachment != null) &&
                        (pendingAttachment == null || AttachmentPolicy.accepts(selectedModel, pendingAttachment.kind))
                    Button(enabled = canSend && !sending && !voiceVisible && !preparingAttachment, onClick = {
                        error = onSend(draft, if (armed) null else pendingAttachment)
                        if (error == null) { draft = ""; pendingUri = null }
                    }, modifier = Modifier.testTag("chat_send")) { Text(if (sending) "Thinking…" else "Send") }
                }
            }
            // Keep the voice controller and shared Settings alive in both modes.
            voiceContent(voiceVisible, settings, { settings = false }, ::returnToChat)
        }
        NavigationBar {
            NavigationBarItem(selected = !voiceVisible, onClick = ::returnToChat, icon = {}, label = { Text("Chat") }, modifier = Modifier.testTag("conversation_nav_chat"))
            NavigationBarItem(selected = voiceVisible, onClick = {
                VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { onEndVoice {} }
                voiceVisible = true
            }, icon = {}, label = { Text("Voice") }, modifier = Modifier.testTag("conversation_nav_voice"))
            NavigationBarItem(selected = false, onClick = onOpenMemory, icon = {}, label = { Text("Memory") }, modifier = Modifier.testTag("memory_open"))
        }
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Conversations") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(history.list(), key = { it.id }) { saved ->
                TextButton(onClick = { error = onSelectConversation(saved.id); if (error == null) showHistory = false }) {
                    Text(saved.title)
                }
            }
            item { TextButton(onClick = { showHistory = false; onOpenVoiceCalls() }) { Text("Saved voice calls") } }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } })
}
