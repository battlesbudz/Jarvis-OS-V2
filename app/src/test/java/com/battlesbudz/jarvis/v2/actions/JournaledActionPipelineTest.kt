package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class JournaledActionPipelineTest {
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("journal-dispatch").toFile()
        try { test(File(directory, "attempts.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun executorObservesDurableIntentThenTypedReceiptReloads() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor  {
            calls++
            assertEquals(ToolTaskState.RUNNING, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state)
            ExecutionResult(true, "85%")
        })
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertEquals(1, calls)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(ToolTaskState.SUCCEEDED, saved.state)
        assertEquals(ExecutionResult.Outcome.SUCCEEDED, saved.resultOutcome)
        assertEquals("85%", saved.result)
    }

    @Test fun validationOrPreDispatchStorageFailureHasZeroEffects() = withFile { file ->
        var calls = 0
        val executor = MobileActionExecutor { calls++; ExecutionResult(true, "effect") }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        assertEquals(ExecutionResult.Outcome.REJECTED_VALIDATION,
            JournaledActionPipeline(ledger, executor).execute(ActionRequest("set_volume", mapOf("level" to "999"))).outcome)
        assertFalse(file.exists())
        val failing = ToolTaskLedger(FileToolTaskStore(file) { _, _ -> error("disk full") })
        assertFalse(JournaledActionPipeline(failing, executor).execute(ActionRequest("read_battery")).succeeded)
        file.writeText("corrupt")
        assertFalse(JournaledActionPipeline(ledger, executor).execute(ActionRequest("read_battery")).succeeded)
        assertEquals(0, calls)
        assertEquals("corrupt", file.readText())
    }

    @Test fun receiptWriteFailureReturnsUnknownWithoutRepeatingEffect() = withFile { file ->
        val goodStore = FileToolTaskStore(file)
        var writes = 0
        val store = object : ToolTaskStore {
            override fun read() = goodStore.read()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
                if (++writes == 3) throw ToolTaskStorageException()
                return goodStore.update(change)
            }
        }
        var calls = 0
        val result = JournaledActionPipeline(ToolTaskLedger(store), MobileActionExecutor  { calls++; ExecutionResult(true, "effect") })
            .execute(ActionRequest("read_battery"))
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(ToolTaskState.RUNNING, goodStore.read().single().state)
        val recovered = ToolTaskLedger(goodStore).recoverAfterRestart().single()
        assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recovered.state)
        assertEquals(1, calls)
    }

    @Test fun cancellationAndProgrammingErrorsPropagateAndFencePossibleEffects() = withFile { file ->
        for (error in listOf(CancellationException("cancel"), IllegalArgumentException("bug"))) {
            file.delete()
            val pipeline = JournaledActionPipeline(ToolTaskLedger(FileToolTaskStore(file)), MobileActionExecutor  { throw error })
            try { pipeline.execute(ActionRequest("read_battery")); fail("Expected original exception") }
            catch (observed: Exception) { assertSame(error, observed) }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state)
        }
    }

    @Test fun permissionAndServiceFailuresRetainTheirDistinctOutcomes() = withFile { file ->
        for ((error, outcome) in listOf(SecurityException() to ExecutionResult.Outcome.DENIED_PERMISSION,
            IllegalStateException() to ExecutionResult.Outcome.UNKNOWN_COMPLETION)) {
            file.delete()
            val result = JournaledActionPipeline(ToolTaskLedger(FileToolTaskStore(file)), MobileActionExecutor  { throw error })
                .execute(ActionRequest("read_battery"))
            assertEquals(outcome, result.outcome)
            assertEquals(outcome, ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().resultOutcome)
        }
    }

    @Test fun secretFillTextRedactedPreJournalButDispatchedVerbatim() = withFile { file ->
        // Pre-journal credential boundary: the journaled attempt redacts the
        // secret argument, while the live dispatch uses the original.
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var dispatched: MobileAction? = null
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction): ExecutionResult {
                dispatched = action
                return ExecutionResult(true, "filled")
            }
            override fun secretArgumentKeys(request: ActionRequest): Set<String> =
                if (request.name == "browse_fill") setOf("text") else emptySet()
        }
        val pipeline = JournaledActionPipeline(ledger, executor)
        val request = ActionRequest(
            "browse_fill",
            mapOf("field" to "f1", "text" to "s3cr3t-pw", "token" to "0123456789abcdef")
        )
        assertTrue(pipeline.execute(request).succeeded)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(CredentialBoundary.REDACTED, saved.request.arguments["text"])
        assertEquals("f1", saved.request.arguments["field"])
        assertEquals("the live dispatch must use the original secret",
            MobileAction.BrowseFill("f1", "s3cr3t-pw", "0123456789abcdef"), dispatched)
        assertFalse("the secret must never reach the bytes on disk",
            file.readText().contains("s3cr3t-pw"))
    }

    @Test fun nonSecretRequestsJournalVerbatim() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val executor = object : MobileActionExecutor, SecretAwareExecutor {
            override fun execute(action: MobileAction) = ExecutionResult(true, "ok")
            override fun secretArgumentKeys(request: ActionRequest): Set<String> = emptySet()
        }
        val pipeline = JournaledActionPipeline(ledger, executor)
        val request = ActionRequest("set_volume", mapOf("level" to "20"))
        assertTrue(pipeline.execute(request).succeeded)
        val saved = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
        assertEquals(mapOf("level" to "20"), saved.request.arguments)
    }
}
