package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection

/**
 * M2 settings surface (D36): saved workflows with enable/disable and the
 * connected tools list. Chat remains the operating surface — creation and
 * revision happen in conversation; this section only flips the explicit
 * enablement that drafts require before they can run.
 */
@Composable
internal fun WorkflowSettingsSection(
    projection: WorkflowSettingsProjection?,
    onSetEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Tools & workflows", style = MaterialTheme.typography.titleMedium)
        Text("Routines you saved. A routine never runs until you enable it here or in chat.",
            style = MaterialTheme.typography.bodySmall)
        if (projection == null) {
            Text("Workflow settings are unavailable right now.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        if (projection.workflows.isEmpty()) {
            Text("No saved routines yet. Describe one in chat — for example, “remind me to charge my phone at 9 PM every day” — and I will draft it for your approval.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        for (row in projection.workflows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(row.summary, style = MaterialTheme.typography.bodySmall)
                    Text("${row.triggersSummary} · ${row.nextRunSummary ?: ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { onSetEnabled(row.id, !row.enabled) },
                    modifier = Modifier.testTag("workflow_toggle_${row.id.take(8)}")
                ) { Text(if (row.enabled) "Disable" else "Enable") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Connected tools", style = MaterialTheme.typography.titleSmall)
        for (tool in projection.tools) {
            Text("${tool.description}: ${tool.state}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
