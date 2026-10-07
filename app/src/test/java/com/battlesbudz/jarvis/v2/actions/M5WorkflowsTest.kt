package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * M5 community workflows and isolated scripts (D41–D44, T19–T20).
 *
 * Covers the versioned manifest format (round-trip, forward-compatible
 * unknown fields, version/kind rejection), export redaction (rule table and
 * end-to-end, no secrets survive), import review/admission (missing
 * dependencies save disabled with a reason; declined imports rejected),
 * setup bindings, the update policy (doc-only auto-apply vs behavior-change
 * review, version pinning), and the isolated script runtime (denials of
 * file/network/tool access, resource-exhaustion termination, cancellation,
 * allowlist behavior) plus engine integration.
 *
 * Deterministic; no network, no Android.
 */
class M5WorkflowsTest {

    private var nowMs = 1_700_000_000_000L
    private val now: () -> Long = { nowMs }
    private fun uid() = UUID.randomUUID().toString()

    private fun batteryStep(id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("read_battery"))

    private fun volumeStep(level: String, id: String = uid()) =
        WorkflowStep.Tool(id, ActionRequest("set_volume", mapOf("level" to level)))

    private fun scriptStep(source: String = "log(\"hi\");", id: String = uid()) =
        WorkflowStep.Script(id, source, listOf("log"))

    private fun definition(
        name: String = "Test routine",
        steps: List<WorkflowStep> = listOf(batteryStep(), volumeStep("20")),
        triggers: List<WorkflowTrigger> = listOf(WorkflowTrigger.Manual)
    ) = WorkflowDefinition(uid(), name, "A test routine.", steps, triggers,
        WorkflowOrigin.CONVERSATION, createdAtMs = nowMs, updatedAtMs = nowMs)

    private fun manifestOf(def: WorkflowDefinition) = WorkflowManifest(
        workflow = def,
        requiredTools = listOf(ToolContract("read_battery", 1), ToolContract("set_volume", 1)),
        permittedScopes = listOf("battery.read", "audio.modify"),
        scriptRuntime = ScriptRuntimeRequirements(
            engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
            maxOutputChars = 8192, requiredHostFunctions = listOf("log")),
        provenance = ManifestProvenance("tester", "test", nowMs),
        documentation = "Test docs."
    )

    private fun fullCaps() = DeviceCapabilities(
        availableTools = mapOf("read_battery" to 1L, "set_volume" to 1L),
        grantedScopes = setOf("battery.read", "audio.modify"),
        scriptRuntime = ScriptRuntimeCapabilities(
            engine = SCRIPT_ENGINE_NAME, maxTimeMs = 10_000L, maxMemoryKb = 8192L,
            maxOutputChars = 8192, hostFunctions = setOf("log"))
    )

    private fun richDefinition(): WorkflowDefinition {
        val batteryId = uid()
        val volumeId = uid()
        return definition(steps = listOf(
            batteryStep(batteryId),
            WorkflowStep.Branch(uid(),
                WorkflowCondition.GreaterThan(WorkflowBinding(batteryId, "battery_percent"), 20.0),
                thenSteps = listOf(volumeStep("20", volumeId)),
                elseSteps = listOf(WorkflowStep.Wait(uid(), WorkflowWait.Timer(60_000L)))),
            WorkflowStep.Adaptive(uid(), "check power",
                listOf(ActionRequest("read_battery")), EffortBudget(3, 60_000L, 2)),
            scriptStep("log(\"done\");")
        ))
    }

    // -- Manifest format --------

    @Test fun manifestRoundTripsEveryStepKind() {
        val def = richDefinition()
        val manifest = manifestOf(def)
        val json = manifest.toJson()
        val parsed = parseWorkflowManifest(json)
        assertEquals(manifest, parsed)
        // Canonical: re-serializing the parse gives identical bytes.
        assertEquals(json, parsed.toJson())
    }

    @Test fun manifestIgnoresUnknownFields() {
        val manifest = manifestOf(definition())
        val json = manifest.toJson()
            .replace("\"manifestVersion\":1", "\"manifestVersion\":1,\"zzz_future\":{\"a\":[1,2]}")
            .replace("\"kind\":\"tool\"", "\"kind\":\"tool\",\"zzz\":true")
        assertEquals(manifest, parseWorkflowManifest(json))
    }

    @Test fun manifestRejectsNewerVersion() {
        val json = manifestOf(definition()).toJson()
            .replace("\"manifestVersion\":1", "\"manifestVersion\":99")
        try {
            parseWorkflowManifest(json)
            fail("expected rejection of manifestVersion 99")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer app version"))
        }
    }

    @Test fun manifestRejectsUnknownStepKind() {
        val json = manifestOf(definition()).toJson()
            .replace("\"kind\":\"tool\"", "\"kind\":\"teleport\"")
        try {
            parseWorkflowManifest(json)
            fail("expected rejection of unknown step kind")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown step kind"))
        }
    }

    @Test fun manifestRejectsInvalidWorkflow() {
        val json = "{\"manifestVersion\":1,\"workflow\":{\"id\":\"" + uid() + "\",\"name\":\"x\"," +
            "\"description\":\"\",\"version\":1,\"maxStepsPerRun\":16,\"origin\":\"conversation\"," +
            "\"createdAtMs\":1,\"updatedAtMs\":1,\"steps\":[],\"triggers\":[{\"kind\":\"manual\"}]}," +
            "\"requiredTools\":[],\"permittedScopes\":[],\"scriptRuntime\":null,\"setupBindings\":[]," +
            "\"provenance\":{\"author\":\"a\",\"source\":\"s\",\"createdAtMs\":1},\"documentation\":\"\"}"
        try {
            parseWorkflowManifest(json)
            fail("expected validation failure for empty steps")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-16"))
        }
    }

    @Test fun manifestRejectsMalformedJson() {
        try {
            parseWorkflowManifest("""{"manifestVersion":1,"workflow":}""")
            fail("expected malformed JSON rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("malformed"))
        }
    }

    // -- Redaction --------

    @Test fun redactionRuleTable() {
        assertNotNull(redactExportValue("api_key", "xyz"))
        assertEquals(true, redactExportValue("api_key", "xyz")!!.sensitive)
        assertNotNull(redactExportValue("authToken", "abc"))
        assertNotNull(redactExportValue("password", "hunter2"))
        assertNotNull(redactExportValue("email", "a@b.com"))
        assertEquals(false, redactExportValue("email", "a@b.com")!!.sensitive)
        assertNotNull(redactExportValue("url", "https://user:pass@internal.example/x"))
        assertEquals(true, redactExportValue("url", "https://user:pass@internal.example/x")!!.sensitive)
        assertNotNull(redactExportValue("url", "https://public.example/x"))
        assertNull(redactExportValue("level", "20"))
        assertNull(redactExportValue("text", "hello world"))
        assertNull(redactExportValue("title", ""))
        assertNull(redactExportValue("app", "Settings"))
    }

    @Test fun exportRedactsSecretsAndListsBindings() {
        val secretUrl = "https://user:s3cret@internal.example.com/hook"
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_website", mapOf("url" to secretUrl))),
            volumeStep("20")
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        // No secret survives in the shared JSON.
        assertFalse(export.manifestJson.contains("s3cret"))
        assertFalse(export.manifestJson.contains("internal.example.com"))
        assertTrue(export.manifestJson.contains("{{setup:url}}"))
        // The binding is listed for the importer to fill in.
        val binding = export.preview.redacted.single { it.binding == "{{setup:url}}" }
        assertTrue(binding.field.contains("arguments[url]"))
        // Preview shows shared vs redacted.
        assertTrue(export.preview.shared.any { it.contains("open_website") })
        assertEquals(1, export.preview.redacted.size)
        // The exported manifest parses and still validates.
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertEquals(1, parsed.setupBindings.size)
        assertEquals("url", parsed.setupBindings[0].name)
        assertTrue(parsed.setupBindings[0].sensitive)
    }

    @Test fun exportLeavesOrdinaryValuesAlone() {
        val def = definition(steps = listOf(volumeStep("20")))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertTrue(export.preview.redacted.isEmpty())
        assertTrue(export.manifestJson.contains("\"level\":\"20\""))
        val parsed = parseWorkflowManifest(export.manifestJson)
        assertTrue(parsed.setupBindings.isEmpty())
    }

    @Test fun exportRedactsSecretsInsideScriptSource() {
        val def = definition(steps = listOf(
            WorkflowStep.Script(uid(), "let u = \"https://hooks.example.com/abc\";\nlog(\"hi\");", emptyList())
        ))
        val export = exportWorkflow(def, author = "tester", nowMs = nowMs)
        assertFalse(export.manifestJson.contains("hooks.example.com"))
        assertTrue(export.manifestJson.contains("{{setup:script_literal_1}}"))
        assertTrue(export.preview.redacted.any { it.field.contains("script literal") })
    }

    // -- Import --------

    @Test fun importReviewFindsMissingDependencies() {
        val json = manifestOf(richDefinition()).toJson()
        val caps = DeviceCapabilities(emptyMap(), emptySet(), scriptRuntime = null)
        val review = reviewWorkflowManifest(json, caps)
        assertFalse(review.ready)
        assertTrue(review.missingTools.any { it.name == "read_battery" })
        assertTrue(review.missingScopes.contains("battery.read"))
        assertTrue(review.runtimeProblems.any { it.contains("not installed") })
        assertTrue(review.previewText.contains("tester"))
    }

    @Test fun importAdmitsDisabledWithReasonWhenDepsMissing() {
        val json = manifestOf(richDefinition()).toJson()
        val review = reviewWorkflowManifest(json, DeviceCapabilities(emptyMap(), emptySet()))
        val decision = admitWorkflowManifest(review, userApproved = true)
        assertTrue(decision is ImportDecision.Admitted)
        decision as ImportDecision.Admitted
        assertFalse(decision.definition.enabled)
        assertNotNull(decision.disabledReason)
        assertTrue(decision.disabledReason!!.contains("read_battery"))
    }

    @Test fun importAdmitsReadyWhenAllPresent() {
        val json = manifestOf(richDefinition()).toJson()
        val review = reviewWorkflowManifest(json, fullCaps())
        assertTrue(review.ready)
        val decision = admitWorkflowManifest(review, userApproved = true)
        assertTrue(decision is ImportDecision.Admitted)
        decision as ImportDecision.Admitted
        assertFalse(decision.definition.enabled) // drafts never run until enabled (T12)
        assertNull(decision.disabledReason)
    }

    @Test fun importDeclinedIsRejected() {
        val review = reviewWorkflowManifest(manifestOf(definition()).toJson(), fullCaps())
        val decision = admitWorkflowManifest(review, userApproved = false)
        assertTrue(decision is ImportDecision.Rejected)
    }

    @Test fun importRejectsUnknownToolVersions() {
        val json = manifestOf(richDefinition()).toJson()
        val caps = fullCaps().copy(availableTools = mapOf("read_battery" to 1L, "set_volume" to 1L))
        // Manifest demands v2 of set_volume; device has v1.
        val v2 = manifestOf(richDefinition()).copy(
            requiredTools = listOf(ToolContract("read_battery", 1), ToolContract("set_volume", 2)))
        val review = reviewWorkflowManifest(v2.toJson(), caps)
        assertTrue(review.missingTools.any { it.name == "set_volume" })
        assertFalse(review.ready)
    }

    // -- Setup bindings --------

    @Test fun resolveSetupBindingsFillsPlaceholders() {
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_website", mapOf("url" to "{{setup:url}}")))
        ))
        val resolved = resolveSetupBindings(def, mapOf("url" to "https://example.com"))
        val args = (resolved.steps[0] as WorkflowStep.Tool).request.arguments
        assertEquals("https://example.com", args["url"])
    }

    @Test fun resolveSetupBindingsThrowsWhenMissing() {
        val def = definition(steps = listOf(
            WorkflowStep.Tool(uid(), ActionRequest("open_website", mapOf("url" to "{{setup:url}}")))
        ))
        try {
            resolveSetupBindings(def, emptyMap())
            fail("expected missing-binding failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("url"))
        }
    }

    // -- Update policy --------

    private fun admittedOf(manifest: WorkflowManifest, enabled: Boolean = false) =
        AdmittedWorkflow(manifest.workflow.copy(enabled = enabled), manifest)

    @Test fun docOnlyUpdateAutoApplies() {
        val old = manifestOf(definition())
        val new = old.copy(
            documentation = "New docs.",
            workflow = old.workflow.copy(description = "New description.", version = 2)
        )
        assertEquals(UpdateClass.DOCUMENTATION_ONLY, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old, enabled = true), new)
        assertTrue(decision is UpdateDecision.AutoApplied)
        decision as UpdateDecision.AutoApplied
        assertEquals("New description.", decision.admitted.definition.description)
        assertTrue(decision.admitted.definition.enabled) // cosmetic update keeps enablement
        assertNull(decision.admitted.pendingUpdate)
    }

    @Test fun scriptChangeNeedsReviewAndPinsRunningVersion() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val changedSteps = oldDef.steps.map {
            if (it is WorkflowStep.Script) it.copy(source = "log(\"changed\");") else it
        }
        val new = old.copy(workflow = oldDef.copy(steps = changedSteps, version = 2))
        assertEquals(UpdateClass.BEHAVIOR_CHANGE, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old), new)
        assertTrue(decision is UpdateDecision.NeedsReview)
        decision as UpdateDecision.NeedsReview
        // Running version stays pinned: the admitted definition is untouched.
        assertEquals(oldDef.steps, decision.admitted.definition.steps)
        assertNotNull(decision.admitted.pendingUpdate)
        assertTrue(decision.changes.any { it.contains("script") })
    }

    @Test fun addedStepNeedsReview() {
        val oldDef = definition()
        val old = manifestOf(oldDef)
        val new = old.copy(workflow = oldDef.copy(
            steps = oldDef.steps + volumeStep("30"), version = 2))
        assertEquals(UpdateClass.BEHAVIOR_CHANGE, classifyWorkflowUpdate(old, new))
        val decision = evaluateWorkflowUpdate(admittedOf(old), new)
        assertTrue(decision is UpdateDecision.NeedsReview)
        decision as UpdateDecision.NeedsReview
        assertTrue(decision.changes.any { it.startsWith("step added") })
    }

    @Test fun approvePendingUpdateApplies() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val changedSteps = oldDef.steps.map {
            if (it is WorkflowStep.Script) it.copy(source = "log(\"v2\");") else it
        }
        val new = old.copy(workflow = oldDef.copy(steps = changedSteps, version = 2))
        val needsReview = evaluateWorkflowUpdate(admittedOf(old), new) as UpdateDecision.NeedsReview
        val applied = approvePendingWorkflowUpdate(needsReview.admitted)
        assertNull(applied.pendingUpdate)
        assertEquals(changedSteps, applied.definition.steps)
        assertEquals(2, applied.manifest.workflow.version)
    }

    @Test fun rejectPendingUpdateDrops() {
        val oldDef = richDefinition()
        val old = manifestOf(oldDef)
        val new = old.copy(workflow = oldDef.copy(description = "x", version = 2),
            documentation = "also changed")
        // description+docs would be doc-only; force behavior change via scopes.
        val new2 = new.copy(permittedScopes = listOf("battery.read"))
        val needsReview = evaluateWorkflowUpdate(admittedOf(old), new2) as UpdateDecision.NeedsReview
        val kept = rejectPendingWorkflowUpdate(needsReview.admitted)
        assertNull(kept.pendingUpdate)
        assertEquals(oldDef.steps, kept.definition.steps)
    }

    @Test fun staleUpdateIsRejected() {
        val old = manifestOf(definition())
        val same = old.copy(documentation = "docs")
        val decision = evaluateWorkflowUpdate(admittedOf(old), same)
        assertTrue(decision is UpdateDecision.Rejected)
    }

    // -- Script runtime: language --------

    @Test fun scriptLanguageBasics() {
        val r = runScript(
            """
            let x = 10;
            let s = "a";
            if (x > 5) { s = s + "b"; } else { s = s + "c"; }
            let i = 0;
            while (i < 3) { i = i + 1; }
            return s;
            """.trimIndent(),
            ScriptHost.empty()
        )
        assertTrue(r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Str("ab"), r.value)
    }

    @Test fun scriptTypeErrorsAreFailuresNotCrashes() {
        assertTrue(runScript("1 + \"a\";", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("1 / 0;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("if (1) { };", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("nope;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("let x = 1;", ScriptHost.empty()) is ScriptResult.Success)
    }

    @Test fun scriptParseErrorsAreFailures() {
        assertTrue(runScript("let = 5;", ScriptHost.empty()) is ScriptResult.Failed)
        assertTrue(runScript("if true { };", ScriptHost.empty()) is ScriptResult.Success) // parens optional
    }

    // -- Script runtime: isolation --------

    @Test fun scriptDeniesFileAccess() {
        val r = runScript("read_file(\"/etc/passwd\");", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Denied)
        r as ScriptResult.Denied
        assertTrue(r.reason.contains("read_file"))
    }

    @Test fun scriptDeniesNetworkAccess() {
        val r = runScript("http_get(\"https://example.com\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesToolAccess() {
        val r = runScript("run_tool(\"read_battery\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesEvenPlausibleUnregisteredFunctions() {
        val r = runScript("notify(\"title\", \"text\");", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptDeniesWithEmptyHost() {
        val r = runScript("log(\"hi\");", ScriptHost.empty())
        assertTrue(r is ScriptResult.Denied)
    }

    @Test fun scriptAllowlistWorks() {
        val r = runScript("log(\"hi\"); return 1 + 2;", ScriptHost.withLog())
        assertTrue(r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(3.0), r.value)
        assertEquals("hi\n", r.output)
    }

    @Test fun scriptKilledOnInfiniteLoop() {
        val r = runScript(
            "let x = 0; while (true) { x = x + 1; }",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 100L)
        )
        assertTrue(r is ScriptResult.Killed)
        r as ScriptResult.Killed
        assertTrue(r.reason.contains("operation budget"))
    }

    @Test fun scriptKilledOnMemoryBomb() {
        val r = runScript(
            "let s = \"0123456789\"; s = s + s; s = s + s; s = s + s;",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 1_000_000L, maxStringChars = 25L)
        )
        assertTrue(r is ScriptResult.Killed)
        r as ScriptResult.Killed
        assertTrue(r.reason.contains("memory budget"))
    }

    @Test fun scriptKilledOnOutputBomb() {
        var n = 0
        val host = ScriptHost(mapOf(
            "spam" to ScriptHostFunction("spam", 0..0) { _, emit ->
                emit("x".repeat(100))
                n++
                ScriptValue.Null
            }
        ))
        val r = runScript(
            "let i = 0; while (i < 100) { spam(); i = i + 1; }",
            host,
            ScriptLimits(maxOps = 1_000_000L, maxOutputChars = 150)
        )
        assertTrue(r is ScriptResult.Killed)
        assertTrue(n < 100)
    }

    @Test fun scriptCancelledImmediately() {
        val r = runScript("let x = 1;", ScriptHost.empty(), isCancelled = { true })
        assertEquals(ScriptResult.Cancelled, r)
    }

    @Test fun scriptCancelledMidRun() {
        var calls = 0
        val r = runScript(
            "let i = 0; while (i < 1000000) { i = i + 1; }",
            ScriptHost.empty(),
            ScriptLimits(maxOps = 10_000_000L),
            isCancelled = { ++calls > 500 }
        )
        assertEquals(ScriptResult.Cancelled, r)
    }

    // -- Script runtime: engine integration --------

    private fun okDispatch(): (ActionRequest) -> ExecutionResult = { ExecutionResult(true, "ok") }

    @Test fun engineRunsScriptStep() {
        val sId = uid()
        val def = definition(steps = listOf(WorkflowStep.Script(sId, "return 40 + 2;", emptyList())))
        val outcome = WorkflowEngine().run(
            def,
            dispatch = okDispatch(),
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals("42", outcome.results[sId]?.get("result"))
        assertTrue(outcome.results.containsKey(sId))
    }

    @Test fun engineScriptStepCanBindResult() {
        val sId = uid()
        val vId = uid()
        val def = definition(steps = listOf(
            WorkflowStep.Script(sId, "return 75;", emptyList()),
            WorkflowStep.Tool(vId, ActionRequest("set_volume", mapOf("level" to "20")),
                bindings = mapOf("level" to WorkflowBinding(sId, "result")))
        ))
        val seen = mutableListOf<ActionRequest>()
        val outcome = WorkflowEngine().run(
            def,
            dispatch = { req -> seen += req; ExecutionResult(true, "ok") },
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Completed)
        assertEquals("75", seen.single().arguments["level"])
    }

    @Test fun engineFailsScriptStepWithoutRuntime() {
        val def = definition(steps = listOf(scriptStep("return 1;")))
        val outcome = WorkflowEngine().run(def, dispatch = okDispatch())
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        outcome as WorkflowRunOutcome.Failed
        assertTrue(outcome.reason.contains("No script runtime"))
    }

    @Test fun engineScriptDenialFailsTheStep() {
        val def = definition(steps = listOf(scriptStep("read_file(\"/x\");")))
        val outcome = WorkflowEngine().run(
            def,
            dispatch = okDispatch(),
            runScript = { step -> step.runWithInterpreter(ScriptHost.empty()) }
        )
        assertTrue(outcome is WorkflowRunOutcome.Failed)
        outcome as WorkflowRunOutcome.Failed
        assertTrue(outcome.reason.contains("denied"))
    }

    @Test fun scriptStepValidation() {
        try {
            WorkflowStep.Script(uid(), "", emptyList()).let { step ->
                definition(steps = listOf(step))
            }
            fail("expected empty script rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-8192"))
        }
        try {
            definition(steps = listOf(WorkflowStep.Script(uid(), "return 1;", listOf("bad name!"))))
            fail("expected bad host-function-name rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("host function"))
        }
    }
}
