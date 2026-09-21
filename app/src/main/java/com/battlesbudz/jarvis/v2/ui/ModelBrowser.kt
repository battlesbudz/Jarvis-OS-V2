package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
    isTested: (LocalModelSpec) -> Boolean,
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
    val uriHandler = LocalUriHandler.current
    fun goBack() { if (family != null) { family = null; detailId = null } else onDismiss() }
    Dialog(onDismissRequest = { goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { goBack() }
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
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { goBack() }) { Text(if (family == null) "Close" else "‹ Families") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(family ?: "Choose a model family", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Text("${phone.name} · ${ModelGuidance.gb(phone.totalRamBytes)} reported RAM",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                OutlinedTextField(value = query, onValueChange = { query = it; detailId = null }, singleLine = true,
                    placeholder = { Text("Search families, models or uses") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("model_search"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                if (family == null) {
                    LazyColumn(state = familyListState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("Open a family to compare uses and phone demands. Labels show Android test evidence or reported issues. Installed status does not affect recommendations or order.",
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
                    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Smallest download → largest", style = MaterialTheme.typography.titleSmall)
                            Text("Size is not speed: compression, model architecture and thinking all matter. A larger model can be worth waiting for on a harder task, but larger does not guarantee a better answer.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Memory labels compare bundle size with total RAM, not currently free RAM. They are a rough screen, not measured working memory. Android, speech, conversation length and driver caches need extra room.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (specs.isEmpty()) item { Text("No matches in this family. Clear the search or return to Families.") }
                        items(specs, key = { it.id }) { spec ->
                            val purpose = ModelGuide.purpose(spec)
                            val fit = ModelGuidance.assess(spec, phone)
                            val installed = isInstalled(spec)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(spec.id, style = MaterialTheme.typography.titleMedium)
                                    ModelCompatibilityLabel(spec)
                                    Text("Good for: " + purpose.tags.joinToString(" · "), color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    Text(purpose.description, style = MaterialTheme.typography.bodySmall)
                                    // Limitations of specialists and vision bundles are visible before selecting.
                                    if (purpose.caveat.isNotBlank()) Text(purpose.caveat, style = MaterialTheme.typography.bodySmall)
                                    Text("Download: " + (spec.downloadBytes?.let(ModelGuidance::gb) ?: "Unknown"),
                                        style = MaterialTheme.typography.labelLarge)
                                    Text(fit.displayLabel, color = if (fit.memoryRisk >= 2 || fit.compatibilityNotice != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)

                                    Text("Response demand: ${fit.workload}" + if (ModelGuide.canThink(spec)) " · Can think longer" else "",
                                        style = MaterialTheme.typography.labelLarge)
                                    if (startingPoint?.id == spec.id) Text("Everyday starting option · lower demand, general chat",
                                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    Text((if (spec.id == selectedId) "Selected · " else "") +
                                        (if (installed) "Installed" else "Not downloaded") +
                                        (if (installed && isTested(spec)) " · Reply test passed" else ""),
                                        style = MaterialTheme.typography.labelSmall)
                                    if (spec.requiresAccess) Text("Publisher terms must be accepted before downloading.", style = MaterialTheme.typography.bodySmall)
                                    ModelGuidance.storageNotice(spec, phone, installed)?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (detailId == spec.id) {
                                        HorizontalDivider()
                                        Text(ModelCompatibility.assess(spec).details, style = MaterialTheme.typography.bodySmall)
                                        if (fit.compatibilityNotice != null) Text("Memory-only estimate below; it does not establish that this model can start.", style = MaterialTheme.typography.labelMedium)
                                        Text(fit.explanation, style = MaterialTheme.typography.bodySmall)
                                        if (fit.compatibilityNotice == null) Text(fit.workloadExplanation, style = MaterialTheme.typography.bodySmall)
                                        fit.deviceExperience?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        Text("Phone chip: ${phone.chip}. No device benchmark is inferred from this name. A short reply test checks loading, not sustained speed or heat.",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (spec.id.contains("26B-A4B", true)) Text("A4B means about 4B active parameters per token, but the full 26B model weights still need memory.",
                                            style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = {
                                            spec.downloadUrl?.substringBefore("/resolve/")?.let { url ->
                                                runCatching { uriHandler.openUri(url) }.onFailure { error = "Could not open the model card." }
                                            }
                                        }) { Text("Read model card") }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { detailId = if (detailId == spec.id) null else spec.id }) {
                                            Text(if (detailId == spec.id) "Less detail" else "Why this rating?")
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
    Text(evidence.summary, color = color, style = MaterialTheme.typography.bodySmall)
}
