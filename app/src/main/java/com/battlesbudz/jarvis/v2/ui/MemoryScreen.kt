package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.memory.MemoryOs
import com.battlesbudz.jarvis.v2.memory.MemoryOutcome
import com.battlesbudz.jarvis.v2.memory.MemoryProposal
import com.battlesbudz.jarvis.v2.memory.MemoryRecord
import com.battlesbudz.jarvis.v2.memory.MemoryReviewStatus
import com.battlesbudz.jarvis.v2.memory.MemorySnapshot
import com.battlesbudz.jarvis.v2.memory.MemorySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A local memory manager. It never automatically extracts conversation content. */
@Composable
internal fun MemoryScreen(memoryOs: MemoryOs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var correctionTarget by remember { mutableStateOf<MemoryRecord?>(null) }
    var sourceEventId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sourceCreatedAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var query by remember { mutableStateOf("") }
    var searchRows by remember { mutableStateOf<List<MemoryRecord>?>(null) }
    var confirmDelete by remember { mutableStateOf<MemoryRecord?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    fun refresh(after: String? = null, fromMutation: Boolean = false) {
        if (busy && !fromMutation) return
        // A successful mutation hands its busy lease to the reload that exposes the saved state.
        busy = true
        scope.launch {
            try {
                val read = withContext(Dispatchers.IO) { memoryOs.read() }
                snapshot = read.snapshot
                error = read.error
                searchRows = null
                notice = after
            } catch (e: Exception) {
                snapshot = null
                error = e.message ?: "Memory manager could not reload saved memories."
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun completeMutation(result: com.battlesbudz.jarvis.v2.memory.MemoryResult, success: () -> Unit = {}) {
        if (result.outcome in setOf(MemoryOutcome.CREATED, MemoryOutcome.APPROVED, MemoryOutcome.REJECTED, MemoryOutcome.DELETED)) {
            success()
            refresh(result.message, fromMutation = true)
        } else {
            busy = false
            notice = null
            error = result.message
        }
    }
    fun runMutation(action: () -> com.battlesbudz.jarvis.v2.memory.MemoryResult, success: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                error = null
                val result = withContext(Dispatchers.IO) { action() }
                completeMutation(result, success)
            } catch (e: Exception) {
                notice = null
                error = e.message ?: "Memory operation failed."
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Memory", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("memory_back")) { Text("Back") }
        }
        Text("Add and review memories saved on this phone. Jarvis never automatically saves conversations: an explicit remember request or this form creates a pending proposal. Only approved memories may be used as historical context in chat or voice.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft,
            onValueChange = {
                if (it != draft) {
                    draft = it
                    // Changed text starts a new manual event; an unchanged retry keeps its full source identity.
                    sourceEventId = UUID.randomUUID().toString()
                    sourceCreatedAtMs = System.currentTimeMillis()
                }
            },
            enabled = !busy,
            label = { Text(if (correctionTarget == null) "What should be remembered?" else "Correct this memory") },
            modifier = Modifier.fillMaxWidth().testTag("memory_new_content"),
            maxLines = 4
        )
        correctionTarget?.let { target ->
            Text("This change will replace: ${target.content}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { correctionTarget = null }, enabled = !busy) { Text("Cancel correction") }
        }
        Button(
            onClick = {
                val target = correctionTarget
                val proposal = MemoryProposal(
                    content = draft,
                    source = MemorySource(sourceEventId, "manual entry", sourceCreatedAtMs),
                    correctsMemoryId = target?.id,
                    expectedTargetRevision = target?.revision
                )
                runMutation({ memoryOs.propose(proposal) }) {
                    draft = ""
                    correctionTarget = null
                    sourceEventId = UUID.randomUUID().toString()
                    sourceCreatedAtMs = System.currentTimeMillis()
                }
            },
            enabled = draft.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().testTag("memory_propose")
        ) { Text(if (correctionTarget == null) "Add for review" else "Submit correction for review") }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it; searchRows = null },
            enabled = !busy,
            label = { Text("Find approved memories") },
            modifier = Modifier.fillMaxWidth().testTag("memory_search_input"),
            singleLine = true
        )
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    try {
                        error = null
                        val result = withContext(Dispatchers.IO) { memoryOs.retrieveResult(query) }
                        if (result.outcome == null) {
                            searchRows = result.memories.map { it.memory }
                            notice = if (searchRows!!.isEmpty()) "No approved memories match that search." else null
                        } else {
                            searchRows = null
                            error = result.message
                        }
                    } catch (e: Exception) {
                        searchRows = null
                        error = e.message ?: "Memory search failed."
                    } finally { busy = false }
                }
            },
            enabled = query.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().testTag("memory_search")
        ) { Text("Search") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("memory_notice")) }

        val rows = searchRows ?: snapshot?.memories.orEmpty()
        if (snapshot == null) {
            if (error == null) Text("Loading saved memories…")
        } else if (rows.isEmpty()) {
            Text(if (searchRows != null) "No matching memories." else "No memories have been added yet.")
        } else {
            rows.sortedWith(compareByDescending<MemoryRecord> { it.updatedAtMs }.thenBy { it.id }).forEach { record ->
                MemoryRow(
                    record = record,
                    busy = busy,
                    onApprove = { runMutation({ memoryOs.approve(record.id, record.revision) }) },
                    onReject = { runMutation({ memoryOs.reject(record.id, record.revision) }) },
                    onCorrect = {
                        correctionTarget = record
                        draft = ""
                        sourceEventId = UUID.randomUUID().toString()
                        sourceCreatedAtMs = System.currentTimeMillis()
                    },
                    onDelete = { confirmDelete = record }
                )
            }
        }
        if (snapshot?.memories?.isNotEmpty() == true) {
            TextButton(onClick = { confirmDeleteAll = true }, enabled = !busy,
                modifier = Modifier.testTag("memory_erase_all")) { Text("Erase all memories") }
        }
    }

    confirmDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Erase this memory?") },
            text = { Text("This also erases any corrections linked to it.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                runMutation({ memoryOs.delete(record.id, record.revision) })
            }, modifier = Modifier.testTag("memory_delete_confirm")) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }, modifier = Modifier.testTag("memory_delete_cancel")) { Text("Cancel") } }
        )
    }
    if (confirmDeleteAll) {
        val generation = snapshot?.generation
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Erase all memories?") },
            text = { Text("This permanently erases every saved memory and its related corrections.") },
            confirmButton = { TextButton(onClick = {
                confirmDeleteAll = false
                if (generation != null) runMutation({ memoryOs.deleteAll(generation) })
            }, modifier = Modifier.testTag("memory_erase_all_confirm")) { Text("Erase all") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }, modifier = Modifier.testTag("memory_erase_all_cancel")) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MemoryRow(
    record: MemoryRecord,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCorrect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.content)
            Text("${record.reviewStatus.name.lowercase().replaceFirstChar { it.titlecase() } } · Added from ${if (record.source.eventSource == "conversation_explicit") "explicit conversation request" else record.source.eventSource}",
                style = MaterialTheme.typography.bodySmall)
            if (record.source.provenance.isNotEmpty()) Text("Details: " + record.source.provenance.joinToString { it.label ?: it.kind },
                style = MaterialTheme.typography.bodySmall)
            Row {
                if (record.reviewStatus == MemoryReviewStatus.PENDING) {
                    TextButton(onClick = onApprove, enabled = !busy, modifier = Modifier.testTag("memory_approve")) { Text("Approve") }
                    TextButton(onClick = onReject, enabled = !busy, modifier = Modifier.testTag("memory_reject")) { Text("Reject") }
                }
                if (record.reviewStatus == MemoryReviewStatus.APPROVED) {
                    TextButton(onClick = onCorrect, enabled = !busy, modifier = Modifier.testTag("memory_correct")) { Text("Correct") }
                }
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.testTag("memory_delete")) { Text("Erase") }
            }
        }
    }
}
