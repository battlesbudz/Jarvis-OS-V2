package com.battlesbudz.jarvis.v2.actions

/**
 * M5 community workflows: import with review, export with redaction (D41–D44, T19).
 *
 * Import never activates anything by itself: every import is admitted as a
 * disabled draft (M2 draft semantics), and the review names exactly what is
 * missing. If required tools, scopes, or the script runtime are unavailable,
 * the draft is saved DISABLED with a clear reason instead of being
 * silently half-working.
 *
 * Export is fail-closed for privacy: accounts, tokens, personal argument
 * values, private endpoints, identifiers and embedded secrets are replaced
 * with `{{setup:name}}` placeholders, and the manifest lists them as setup
 * bindings the importer must fill in. [ExportPreview] shows what will be
 * shared versus what was redacted, so the exporter reviews before sending.
 */

// ---------------------------------------------------------------------------
// Capabilities & review
// ---------------------------------------------------------------------------

/** What the on-phone script runtime offers (null on [DeviceCapabilities] = none installed). */
data class ScriptRuntimeCapabilities(
    val engine: String,
    val maxTimeMs: Long,
    val maxMemoryKb: Long,
    val maxOutputChars: Int,
    val hostFunctions: Set<String>
)

/** What this device can run: tool catalog versions, granted scopes, script runtime. */
data class DeviceCapabilities(
    val availableTools: Map<String, Long>,
    val grantedScopes: Set<String>,
    val scriptRuntime: ScriptRuntimeCapabilities? = null
)

data class ImportReview(
    val manifest: WorkflowManifest,
    val missingTools: List<ToolContract>,
    val missingScopes: List<String>,
    val runtimeProblems: List<String>,
    val setupBindings: List<SetupBinding>,
    val previewText: String
) {
    /** True when everything the manifest needs is present on this device. */
    val ready: Boolean get() = missingTools.isEmpty() && missingScopes.isEmpty() && runtimeProblems.isEmpty()
}

/**
 * Parse [json] and check it against [caps]. Never throws for a missing
 * dependency — those are reported in the review. Throws
 * IllegalArgumentException only for a malformed manifest.
 */
fun reviewWorkflowManifest(json: String, caps: DeviceCapabilities): ImportReview {
    val manifest = parseWorkflowManifest(json)
    val usedTools = collectManifestToolNames(manifest.workflow.steps)
    val contracts = (manifest.requiredTools + usedTools.map { ToolContract(it, 1) })
        .groupBy { it.name }
        .map { (name, cs) -> ToolContract(name, cs.maxOf { it.minVersion }) }
    val missingTools = contracts.filter { (caps.availableTools[it.name] ?: 0L) < it.minVersion }
    val missingScopes = manifest.permittedScopes.filter { it !in caps.grantedScopes }
    val runtimeProblems = checkScriptRuntime(manifest.scriptRuntime, caps.scriptRuntime)
    val preview = buildString {
        appendLine(manifest.workflow.previewText())
        appendLine()
        appendLine("Shared by ${manifest.provenance.author} via ${manifest.provenance.source}.")
        if (manifest.documentation.isNotBlank()) appendLine(manifest.documentation)
        if (manifest.setupBindings.isNotEmpty())
            appendLine("${manifest.setupBindings.size} value(s) were redacted by the sender and need filling in before enabling.")
        if (!ready(missingTools, missingScopes, runtimeProblems))
            appendLine("Missing on this device: " + listOf(
                missingTools.map { "tool ${it.name} (v${it.minVersion})" },
                missingScopes.map { "scope $it" },
                runtimeProblems
            ).flatten().joinToString("; "))
    }
    return ImportReview(manifest, missingTools, missingScopes, runtimeProblems, manifest.setupBindings, preview)
}

private fun ready(missingTools: List<ToolContract>, missingScopes: List<String>, runtimeProblems: List<String>) =
    missingTools.isEmpty() && missingScopes.isEmpty() && runtimeProblems.isEmpty()

private fun checkScriptRuntime(
    required: ScriptRuntimeRequirements?,
    caps: ScriptRuntimeCapabilities?
): List<String> {
    if (required == null) return emptyList()
    if (caps == null) return listOf("script runtime “${required.engine}” is not installed on this device")
    val problems = mutableListOf<String>()
    if (caps.engine != required.engine)
        problems += "script engine “${required.engine}” is not available (have “${caps.engine}”)"
    if (caps.maxTimeMs < required.maxTimeMs)
        problems += "script runtime allows ${caps.maxTimeMs}ms but the workflow needs ${required.maxTimeMs}ms"
    if (caps.maxMemoryKb < required.maxMemoryKb)
        problems += "script runtime allows ${caps.maxMemoryKb}KB but the workflow needs ${required.maxMemoryKb}KB"
    if (caps.maxOutputChars < required.maxOutputChars)
        problems += "script runtime allows ${caps.maxOutputChars} output chars but the workflow needs ${required.maxOutputChars}"
    val missingFns = required.requiredHostFunctions.filter { it !in caps.hostFunctions }
    if (missingFns.isNotEmpty()) problems += "script host functions not available: ${missingFns.joinToString(", ")}"
    return problems
}

sealed interface ImportDecision {
    /** Admitted as a disabled draft. [disabledReason] is null when fully ready. */
    data class Admitted(val definition: WorkflowDefinition, val disabledReason: String?) : ImportDecision
    data class Rejected(val reason: String) : ImportDecision
}

/**
 * Decide on a reviewed manifest. The definition is always admitted disabled
 * (M2: drafts never run until an explicit enable); [disabledReason] tells
 * the UI why it cannot be enabled yet, or null when it is ready to enable.
 */
fun admitWorkflowManifest(review: ImportReview, userApproved: Boolean): ImportDecision {
    if (!userApproved) return ImportDecision.Rejected("Import declined during review.")
    val reasons = buildList {
        review.missingTools.forEach { add("tool “${it.name}” (needs v${it.minVersion}) is not available on this device") }
        review.missingScopes.forEach { add("scope “$it” is not granted on this device") }
        addAll(review.runtimeProblems)
        if (review.setupBindings.isNotEmpty())
            add("${review.setupBindings.size} redacted value(s) need filling in: " +
                review.setupBindings.joinToString(", ") { it.name })
    }
    return ImportDecision.Admitted(
        definition = review.manifest.workflow.copy(enabled = false),
        disabledReason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
    )
}

/**
 * Fill the `{{setup:name}}` placeholders left by export redaction.
 * Throws IllegalArgumentException if any placeholder has no value.
 */
fun resolveSetupBindings(definition: WorkflowDefinition, values: Map<String, String>): WorkflowDefinition {
    val missing = mutableSetOf<String>()
    fun resolve(value: String): String {
        var out = value
        var i = out.indexOf("{{setup:")
        while (i >= 0) {
            val end = out.indexOf("}}", i)
            if (end < 0) break
            val name = out.substring(i + "{{setup:".length, end)
            val replacement = values[name]
            if (replacement == null) {
                missing += name
                i = out.indexOf("{{setup:", end)
            } else {
                out = out.substring(0, i) + replacement + out.substring(end + 2)
                i = out.indexOf("{{setup:", i + replacement.length)
            }
        }
        return out
    }
    fun resolveStep(step: WorkflowStep): WorkflowStep = when (step) {
        is WorkflowStep.Tool -> step.copy(
            request = step.request.copy(arguments = step.request.arguments.mapValues { resolve(it.value) }))
        is WorkflowStep.Branch -> step.copy(
            thenSteps = step.thenSteps.map(::resolveStep),
            elseSteps = step.elseSteps.map(::resolveStep))
        is WorkflowStep.Wait -> step
        is WorkflowStep.Adaptive -> step.copy(
            candidates = step.candidates.map { it.copy(arguments = it.arguments.mapValues { e -> resolve(e.value) }) })
        is WorkflowStep.Script -> step.copy(source = resolve(step.source))
    }
    val resolved = definition.copy(steps = definition.steps.map(::resolveStep))
    require(missing.isEmpty()) { "Setup values still missing: ${missing.sorted().joinToString(", ")}" }
    return resolved
}

// ---------------------------------------------------------------------------
// Export with redaction
// ---------------------------------------------------------------------------

data class RedactionRecord(val field: String, val binding: String)
data class ExportPreview(val shared: List<String>, val redacted: List<RedactionRecord>)
data class WorkflowExport(val manifestJson: String, val preview: ExportPreview)

/**
 * Export [definition] as a shareable manifest JSON.
 *
 * Redaction (deterministic; see [redactExportValue]):
 * - argument keys hinting at secrets (token, secret, password, api_key,
 *   auth, credential, …) → `{{setup:<key>}}`
 * - identifier keys (email, phone, account, username, …) → placeholder
 * - email-looking or URL-looking values → placeholder (private endpoints)
 * - string literals inside on-phone scripts that look like URLs or emails
 *   → placeholder
 *
 * Everything redacted becomes a [SetupBinding] the importer must fill in.
 * The preview lists exactly what is shared versus redacted.
 */
fun exportWorkflow(
    definition: WorkflowDefinition,
    author: String,
    source: String = "export",
    nowMs: Long = System.currentTimeMillis()
): WorkflowExport {
    val redactions = mutableListOf<RedactionRecord>()
    val bindings = linkedMapOf<String, SetupBinding>()

    fun bind(name: String, description: String, sensitive: Boolean): String {
        bindings.getOrPut(name) { SetupBinding(name, description, sensitive) }
        return "{{setup:$name}}"
    }

    fun redactStep(step: WorkflowStep, path: String): WorkflowStep = when (step) {
        is WorkflowStep.Tool -> {
            val newArgs = step.request.arguments.mapValues { (k, v) ->
                val r = redactExportValue(k, v)
                if (r != null) {
                    val field = "$path.arguments[$k]"
                    val placeholder = bind(
                        r.bindingName,
                        "Value for “$k” used by the “${step.request.name}” step — fill in after import.",
                        r.sensitive
                    )
                    redactions += RedactionRecord(field, placeholder)
                    placeholder
                } else v
            }
            step.copy(request = step.request.copy(arguments = newArgs))
        }
        is WorkflowStep.Branch -> step.copy(
            thenSteps = step.thenSteps.mapIndexed { i, s -> redactStep(s, "$path.then[$i]") },
            elseSteps = step.elseSteps.mapIndexed { i, s -> redactStep(s, "$path.else[$i]") }
        )
        is WorkflowStep.Wait -> step
        is WorkflowStep.Adaptive -> step.copy(
            candidates = step.candidates.mapIndexed { i, c ->
                c.copy(arguments = c.arguments.mapValues { (k, v) ->
                    val r = redactExportValue(k, v)
                    if (r != null) {
                        val field = "$path.candidates[$i].arguments[$k]"
                        val placeholder = bind(
                            r.bindingName,
                            "Value for “$k” used by an adaptive candidate — fill in after import.",
                            r.sensitive
                        )
                        redactions += RedactionRecord(field, placeholder)
                        placeholder
                    } else v
                })
            }
        )
        is WorkflowStep.Script -> {
            val (newSource, scriptRecords) = redactScriptLiterals(step.source, "$path.script literal") { name, desc, sensitive ->
                bind(name, desc, sensitive)
            }
            redactions += scriptRecords
            step.copy(source = newSource)
        }
    }

    val redactedSteps = definition.steps.mapIndexed { i, s -> redactStep(s, "steps[$i]") }
    val redactedDef = definition.copy(steps = redactedSteps, enabled = false)

    val toolNames = collectManifestToolNames(redactedSteps).distinct()
    val scopes = toolNames.flatMap { ToolSourcePolicy.requiredScopes(it) }.distinct().sorted()
    val scriptSteps = collectScriptSteps(redactedSteps)
    val manifest = WorkflowManifest(
        workflow = redactedDef,
        requiredTools = toolNames.map { ToolContract(it, MobileToolCatalog.VERSION.toLong()) },
        permittedScopes = scopes,
        scriptRuntime = if (scriptSteps.isEmpty()) null else ScriptRuntimeRequirements(
            engine = SCRIPT_ENGINE_NAME,
            maxTimeMs = DEFAULT_SCRIPT_MAX_TIME_MS,
            maxMemoryKb = DEFAULT_SCRIPT_MAX_MEMORY_KB,
            maxOutputChars = DEFAULT_SCRIPT_MAX_OUTPUT_CHARS,
            requiredHostFunctions = scriptSteps.flatMap { it.requiredHostFunctions }.distinct().sorted()
        ),
        setupBindings = bindings.values.toList(),
        provenance = ManifestProvenance(author, source, nowMs),
        documentation = ""
    )
    val shared = buildList {
        add("workflow “${definition.name}” (${redactedSteps.size} top-level step(s))")
        add("triggers: ${definition.triggers.size}")
        add("tools: ${toolNames.joinToString(", ")}")
        if (scopes.isNotEmpty()) add("scopes: ${scopes.joinToString(", ")}")
        if (scriptSteps.isNotEmpty()) add("${scriptSteps.size} on-phone script(s)")
        add("provenance.author as “$author”")
    }
    return WorkflowExport(manifest.toJson(), ExportPreview(shared, redactions.toList()))
}

// ---------------------------------------------------------------------------
// Redaction rules
// ---------------------------------------------------------------------------

internal data class ValueRedaction(val bindingName: String, val sensitive: Boolean)

private val SECRET_KEY_HINTS = listOf(
    "token", "secret", "password", "passwd", "api_key", "apikey",
    "auth", "credential", "private_key", "access_key", "bearer", "session_key"
)
private val IDENTIFIER_KEYS = setOf(
    "email", "phone", "phone_number", "account", "account_id",
    "user_id", "username", "user", "device_id"
)
private val EMAIL_LIKE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

private fun bindingNameFor(key: String): String {
    val clean = key.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')
    return clean.ifEmpty { "value" }.take(48)
}

/** Returns non-null when [value] must be redacted. Visible for unit tests of the rule table. */
internal fun redactExportValue(argKey: String, value: String): ValueRedaction? {
    if (value.isBlank()) return null
    val key = argKey.lowercase()
    if (SECRET_KEY_HINTS.any { it in key }) return ValueRedaction(bindingNameFor(argKey), sensitive = true)
    if (key in IDENTIFIER_KEYS) return ValueRedaction(bindingNameFor(argKey), sensitive = false)
    if (EMAIL_LIKE.matches(value)) return ValueRedaction(bindingNameFor(argKey), sensitive = false)
    if (value.startsWith("http://") || value.startsWith("https://"))
        return ValueRedaction(bindingNameFor(argKey), sensitive = '@' in value)
    return null
}

/**
 * Redact URL/email-looking string literals inside a script source, in one
 * pass. [bind] registers each binding and returns its `{{setup:name}}`
 * placeholder. Returns the rewritten source and the redaction records.
 */
private fun redactScriptLiterals(
    source: String,
    fieldPrefix: String,
    bind: (name: String, description: String, sensitive: Boolean) -> String
): Pair<String, List<RedactionRecord>> {
    val out = StringBuilder()
    val records = mutableListOf<RedactionRecord>()
    var i = 0
    var n = 0
    while (i < source.length) {
        if (source[i] == '"') {
            val end = scanStringLiteral(source, i)
            val literal = unescapeLiteral(source.substring(i + 1, end - 1))
            val needs = EMAIL_LIKE.matches(literal) ||
                literal.startsWith("http://") || literal.startsWith("https://")
            if (needs) {
                n++
                val placeholder = bind(
                    "script_literal_$n",
                    "Redacted text inside an on-phone script — fill in after import.",
                    '@' in literal
                )
                records += RedactionRecord("$fieldPrefix #$n", placeholder)
                out.append("\"$placeholder\"")
            } else out.append(source, i, end)
            i = end
        } else { out.append(source[i]); i++ }
    }
    return out.toString() to records
}

private fun scanStringLiteral(source: String, open: Int): Int {
    var i = open + 1
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i += 2
            '"' -> return i + 1
            else -> i++
        }
    }
    return source.length
}

private fun unescapeLiteral(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> append(s[i + 1])
            }
            i += 2
        } else { append(c); i++ }
    }
}.toString()

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

internal fun collectManifestToolNames(steps: List<WorkflowStep>): List<String> {
    val names = mutableListOf<String>()
    fun visit(step: WorkflowStep) {
        when (step) {
            is WorkflowStep.Tool -> names += step.request.name
            is WorkflowStep.Branch -> { step.thenSteps.forEach(::visit); step.elseSteps.forEach(::visit) }
            is WorkflowStep.Wait -> Unit
            is WorkflowStep.Adaptive -> names += step.candidates.map { it.name }
            is WorkflowStep.Script -> Unit
        }
    }
    steps.forEach(::visit)
    return names
}

private fun collectScriptSteps(steps: List<WorkflowStep>): List<WorkflowStep.Script> {
    val out = mutableListOf<WorkflowStep.Script>()
    fun visit(step: WorkflowStep) {
        when (step) {
            is WorkflowStep.Script -> out += step
            is WorkflowStep.Branch -> { step.thenSteps.forEach(::visit); step.elseSteps.forEach(::visit) }
            else -> Unit
        }
    }
    steps.forEach(::visit)
    return out
}
