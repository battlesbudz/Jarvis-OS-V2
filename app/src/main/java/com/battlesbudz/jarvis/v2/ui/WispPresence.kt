package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Shared app chrome keeps the persistent character outside every navigation destination. */
@Composable
internal fun WispAppFrame(presence: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
        presence()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** The one app-level character. It observes existing owners and never owns a call or task. */
@Composable
internal fun WispPresence(
    history: ConversationHistory,
    chatBusy: StateFlow<Boolean>,
    callState: StateFlow<VoiceSessionState>,
    voicePlayback: StateFlow<VoicePlaybackFrame>,
    phoneTasks: StateFlow<ToolTaskJournal?>?,
    phoneTaskError: StateFlow<String?>?,
    agentActivity: StateFlow<AgentActivitySnapshot?>?,
    setupActivity: WispPresentation? = null
) {
    val thread by history.current.collectAsStateWithLifecycle()
    val busy by chatBusy.collectAsStateWithLifecycle()
    val call by callState.collectAsStateWithLifecycle()
    val armed by VoiceSessionUi.armed.collectAsStateWithLifecycle()
    val phase by VoiceSessionUi.phase.collectAsStateWithLifecycle()
    val paused by VoiceSessionUi.paused.collectAsStateWithLifecycle()
    val journal by (phoneTasks?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<ToolTaskJournal?>(null) })
    val error by (phoneTaskError?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<String?>(null) })
    val observed by (agentActivity?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<AgentActivitySnapshot?>(null) })
    val receipts = remember { WispReceiptTracker() }
    var receipt by remember(thread.id) { mutableStateOf<WispPresentation?>(null) }
    LaunchedEffect(journal, thread.id) {
        receipts.update(journal, thread.id)?.let { receipt = it }
    }
    LaunchedEffect(receipt?.taskKey) {
        if (receipt != null) {
            delay(if (receipt?.activity == WispActivity.SUCCESS) 2_600 else 5_000)
            receipt = null
        }
    }
    val activity = observed?.takeIf { it.conversationId == thread.id }?.let {
        WispPresentation(
            if (it.kind == com.battlesbudz.jarvis.v2.presentation.AgentActivityKind.ERROR) WispActivity.ERROR else WispActivity.CHECKING,
            it.label, taskKey = "activity:${it.operationId}")
    } ?: setupActivity
    val presentation = WispPresenter.present(thread.id, journal, error, busy, armed, phase, call, paused, activity, receipt)
    // The header reserves its own small area: transcripts and controls never sit behind Wisp.
    val compact = LocalConfiguration.current.screenHeightDp < 500
    Column(Modifier.fillMaxWidth().testTag("jarvis_wisp").semantics {
        contentDescription = "Jarvis: ${presentation.label}"
        stateDescription = presentation.activity.name
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        WispAudioCharacter(presentation, voicePlayback, armed, phase, paused,
            Modifier.size(width = if (compact) 116.dp else 168.dp, height = if (compact) 66.dp else 104.dp))
        Text(presentation.label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("jarvis_wisp_status").semantics {
                contentDescription = listOfNotNull(presentation.label, presentation.detail).distinct().joinToString(". ")
            })
    }
}

/** Isolate high-rate envelope collection from the app chrome and transcript composition. */
@Composable
private fun WispAudioCharacter(presentation: WispPresentation, playbackFlow: StateFlow<VoicePlaybackFrame>,
    armed: Boolean, phase: com.battlesbudz.jarvis.v2.voice.VoicePhase, paused: Boolean, modifier: Modifier) {
    val microphone by VoiceSessionUi.level.collectAsStateWithLifecycle()
    val playback by playbackFlow.collectAsStateWithLifecycle()
    val level = WispPresenter.audioLevel(presentation.activity, phase, armed, paused, microphone, playback.level)
    WispCharacter(presentation, level, modifier)
}
