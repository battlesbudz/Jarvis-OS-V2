package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.battlesbudz.jarvis.v2.ai.*

/** Family first, then a bounded lazy list of model cards. Browsing never selects/downloads a model. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ModelBrowser(
    phone: PhoneProfile,
    selectedId: String,
    isInstalled: (LocalModelSpec) -> Boolean,
    onSelect: (LocalModelSpec) -> String?,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var warningId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val families = remember(query) { ModelGuide.families(query = query) }
    val familyListState = rememberLazyListState()
    fun goBack() { if (family != null) { family = null; detailId = null } else onDismiss() }
    Dialog(onDismissRequest = { goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { goBack() }
        detailId?.let { id -> ModelCatalog.find(id)?.let { spec ->
            ModelDetails(spec, phone) { detailId = null }
        } }
        warningId?.let { id -> ModelCatalog.find(id)?.let { spec ->
            AlertDialog(onDismissRequest = { warningId = null },
                title = { Text("Known issue — read before choosing") },
                text = { Text(ModelCompatibility.assess(spec).summary) },
                confirmButton = { TextButton(onClick = {
                    warningId = null
                    error = onSelect(spec)
                    if (error == null) onDismiss()
                }, modifier = Modifier.testTag("model_issue_continue")) { Text("Choose anyway") } },
                dismissButton = { TextButton(onClick = { warningId = null }) { Text("Cancel") } })
        } }

        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
            // The dialog owns edge-to-edge insets, including Android 15/16 three-button navigation.
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { goBack() }) { Text(if (family == null) "Close" else "‹ Families") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(family ?: "Choose a model family", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Text(phone.name,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                OutlinedTextField(value = query, onValueChange = { query = it; detailId = null }, singleLine = true,
                    placeholder = { Text("Search families, models or uses") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("model_search"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (family == null) {
                    LazyColumn(state = familyListState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Choose a family, then compare models from smallest to largest.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (families.isEmpty()) item { Text("No matching models. Try a different name or use, such as coding.") }
                        items(families.entries.toList(), key = { it.key }) { (name, specs) ->
                            OutlinedCard(onClick = { family = name; detailId = null; error = null }, modifier = Modifier.fillMaxWidth().testTag("model_family_$name")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$name  ›", style = MaterialTheme.typography.titleMedium)
                                    Text(specs.map { it.provider }.distinct().joinToString(), style = MaterialTheme.typography.labelSmall)
                                    Text(specs.flatMap { ModelGuide.purpose(it).tags }.distinct().take(5).joinToString(" · "),
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                    Text("${specs.size} models · " + specs.mapNotNull { it.downloadBytes }.let { sizes ->
                                        if (sizes.isEmpty()) "Size unknown" else "${ModelGuidance.gb(sizes.min())} – ${ModelGuidance.gb(sizes.max())} downloads"
                                    }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else key(family, query) {
                    val specs = families[family].orEmpty()
                    val startingPoint = ModelGuide.startingPoint(ModelGuide.families()[family].orEmpty(), phone)
                    LazyColumn(modifier = Modifier.weight(1f).testTag("model_list"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Smallest download → largest", style = MaterialTheme.typography.titleSmall)

                        }
                        if (specs.isEmpty()) item { Text("No matches in this family. Clear the search or return to Families.") }
                        items(specs, key = { it.id }) { spec ->
                            val fit = ModelGuidance.assess(spec, phone)
                            val installed = isInstalled(spec)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(spec.id, style = MaterialTheme.typography.titleMedium)
                                    ModelCompatibilityLabel(spec)
                                    Text(ModelGuide.quickUse(spec), color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodyMedium)
                                    ModelGuide.visibleLimitation(spec)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Download: " + (spec.downloadBytes?.let(ModelGuidance::gb) ?: "Size unknown") +
                                        if (installed) " · Installed" else "",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(fit.quickMemoryLabel,
                                        color = if (fit.memoryWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (startingPoint?.id == spec.id) Text("Suggested starting model",
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    ModelGuidance.storageNotice(spec, phone, installed)?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { detailId = spec.id }, modifier = Modifier.testTag("model_details_${spec.id}")) {
                                            Text("Details")
                                        }
                                        Button(modifier = Modifier.testTag("model_choose_${spec.id}"), onClick = { if (ModelCompatibility.assess(spec).confirmBeforeSelection) warningId = spec.id
                                            else { error = onSelect(spec); if (error == null) onDismiss() } }) {
                                            Text(if (spec.id == selectedId) "Selected" else "Choose")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelCompatibilityLabel(spec: LocalModelSpec) {
    val evidence = ModelCompatibility.assess(spec)
    val color = if (evidence.status == ModelEvidenceStatus.ISSUE) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Text(evidence.status.label, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("model_evidence_${spec.id}"))
    if (evidence.status == ModelEvidenceStatus.ISSUE) {
        Text(evidence.summary, color = color, style = MaterialTheme.typography.bodySmall)
    } else if (evidence.status == ModelEvidenceStatus.EXPERIMENTAL) {
        Text("Not yet verified for this Jarvis setup.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Shared explanation, revealed on request instead of repeated throughout Settings. */
@Composable
internal fun ModelDetails(spec: LocalModelSpec, phone: PhoneProfile, onDismiss: () -> Unit) {
    val purpose = ModelGuide.purpose(spec)
    val fit = ModelGuidance.assess(spec, phone)
    val evidence = ModelCompatibility.assess(spec)
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About this model") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close details") } },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(spec.id, style = MaterialTheme.typography.titleSmall)
                Text(purpose.description)
                if (purpose.caveat.isNotBlank()) Text(purpose.caveat)
                HorizontalDivider()
                Text(evidence.status.label, fontWeight = FontWeight.Bold)
                Text(evidence.summary)
                if (evidence.details.isNotBlank()) Text(evidence.details)
                Text("An Android test is not a speed or reliability guarantee for your phone.")
                HorizontalDivider()
                Text("Phone estimate", fontWeight = FontWeight.Bold)
                Text(fit.explanation)
                Text(fit.workloadExplanation)
                fit.deviceExperience?.let { Text(it) }
                Text("${phone.name} · ${ModelGuidance.gb(phone.totalRamBytes)} RAM · ${ModelGuidance.gb(phone.freeStorageBytes)} free storage")
                Text("Download size is not measured memory use. These estimates do not change when you install a model. Phone checks stay on your device.")
                val source = evidence.source ?: spec.downloadUrl?.substringBefore("/resolve/")
                if (source != null) TextButton(onClick = {
                    runCatching { uriHandler.openUri(source) }.onFailure { linkError = "Could not open the publisher page." }
                }) { Text("Publisher evidence") }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}
