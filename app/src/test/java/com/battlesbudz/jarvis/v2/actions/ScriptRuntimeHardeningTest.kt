package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Script runtime hardening (slice 3, item G): bounded/cancellable parsing,
 * cancellation on the alarm execution path, and while-loop return
 * propagation.
 *
 * Deterministic; no network, no Android.
 */
class ScriptRuntimeHardeningTest {

    private fun uid() = UUID.randomUUID().toString()

    // -- Bounded parsing --------

    @Test fun deeplyNestedBlocksAbortParsingInsteadOfOverflowing() {
        // Past MAX_BLOCK_DEPTH nested if-blocks: the parser must report a
        // typed failure, not exhaust the stack. MAX_EXPR_DEPTH alone does
        // not cover statement blocks (parseStmt/parseBlock recursion).
        val source = "if true { ".repeat(MAX_BLOCK_DEPTH + 50) + "1;" +
            " }".repeat(MAX_BLOCK_DEPTH + 50)
        val r = runScript(source, ScriptHost.empty())
        assertTrue("expected Failed, got $r", r is ScriptResult.Failed)
        r as ScriptResult.Failed
        assertTrue("reason: ${r.reason}", r.reason.contains("too deeply nested"))
    }

    @Test fun parseStatementBudgetBoundsFlatPrograms() {
        // A flat program past MAX_PARSE_STMTS aborts during parsing with a
        // typed failure instead of parsing unboundedly.
        val r = runScript("1;".repeat(MAX_PARSE_STMTS + 1), ScriptHost.empty())
        assertTrue("expected Failed, got $r", r is ScriptResult.Failed)
        r as ScriptResult.Failed
        assertTrue("reason: ${r.reason}", r.reason.contains("too many statements"))
    }

    @Test fun parseCancellationAbortsMidParse() {
        // Cancellation is re-checked while statements are parsed, not only
        // before parsing: a long program aborts as Cancelled once the flag
        // trips, before the statement budget is even reached.
        var calls = 0
        val r = runScript(
            "1;".repeat(MAX_PARSE_STMTS * 2),
            ScriptHost.empty(),
            isCancelled = { ++calls > 1_000 }
        )
        assertEquals(ScriptResult.Cancelled, r)
    }

    // -- While-loop return propagation --------

    @Test fun whileReturnPropagatesImmediately() {
        // A function-free `while true { return 1; }` must return 1
        // promptly — not keep looping until the operation budget is
        // exhausted.
        val r = runScript("while (true) { return 1; }", ScriptHost.empty())
        assertTrue("expected Success, got $r", r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(1.0), r.value)
        assertTrue("return must not run to the budget: ${r.opsUsed} ops", r.opsUsed < 100)
    }

    @Test fun whileReturnInsideNestedIfPropagates() {
        val r = runScript(
            "let i = 0; while (true) { if (i > 2) { return 42; } i = i + 1; }",
            ScriptHost.empty()
        )
        assertTrue("expected Success, got $r", r is ScriptResult.Success)
        r as ScriptResult.Success
        assertEquals(ScriptValue.Num(42.0), r.value)
    }

    // -- Alarm execution path --------

    @Test fun alarmPathCancellationReachesTheInterpreter() {
        // The coordinator's alarm path used to wire isCancelled = { false },
        // making alarm-run scripts uninterruptible; it now passes the
        // coordinator scope's cancellation. This pins the receiving
        // contract: the exact runWithInterpreter call shape the alarm path
        // uses must honor a mid-run cancellation.
        val step = WorkflowStep.Script(
            uid(), "let i = 0; while (i < 1000000) { i = i + 1; }", emptyList())
        var calls = 0
        val execution = step.runWithInterpreter(
            ScriptHost.empty(),
            ScriptLimits(maxOps = 10_000_000L),
            isCancelled = { ++calls > 500 }
        )
        assertEquals(ScriptExecution.Cancelled, execution)
    }
}
