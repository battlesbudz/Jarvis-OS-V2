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

    @Test fun observerSeesDurableRunningBeforeExecutorAndReceiptAfterward() = withFile { file ->
        for (bound in listOf(false, true)) {
            file.delete()
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val observed = mutableListOf<ToolTaskState>()
            var calls = 0
            val request = ActionRequest("read_battery")
            val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
                calls++
                assertEquals(listOf(ToolTaskState.RUNNING), observed)
                ExecutionResult(true, "85%")
            }, onJournalChanged = {
                observed += ToolTaskLedger(FileToolTaskStore(file)).snapshot().single().state
            })
            val result = if (bound) {
                val group = ledger.admit(listOf(request), "conversation-a")
                pipeline.executeBound(requireNotNull(ledger.get(group.attemptIds.single())), request)
            } else pipeline.execute(request)

            assertTrue(result.succeeded)
            assertEquals(1, calls)
            assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.SUCCEEDED), observed)
        }
    }

    @Test fun observerSeesFailureAndCancellationWithoutChangingTheOriginalOutcome() = withFile { file ->
        for (error in listOf(CancellationException("cancel"), IllegalArgumentException("bug"))) {
            file.delete()
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val observed = mutableListOf<ToolTaskState>()
            var calls = 0
            val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor { calls++; throw error },
                onJournalChanged = { observed += ledger.snapshot().single().state })

            try { pipeline.execute(ActionRequest("read_battery")); fail("Expected original exception") }
            catch (caught: Exception) { assertSame(error, caught) }
            assertEquals(1, calls)
            assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.UNKNOWN_OUTCOME), observed)
        }
    }

    @Test fun observerFailureCannotBlockRetryOrReplaceDispatch() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        var observations = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "85%")
        }, onJournalChanged = {
            observations++
            throw IllegalStateException("fixture presentation failure")
        })

        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        assertEquals(1, calls)
        assertEquals(2, observations)
        assertEquals(ToolTaskState.SUCCEEDED, ledger.snapshot().single().state)
    }

    @Test fun rejectedRequestsNeverPublishRunningOrCallExecutor() = withFile { file ->
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        var calls = 0
        var observations = 0
        val pipeline = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "effect")
        }, onJournalChanged = { observations++ })

        assertEquals(ExecutionResult.Outcome.REJECTED_VALIDATION,
            pipeline.execute(ActionRequest("set_volume", mapOf("level" to "999"))).outcome)
        assertEquals(0, calls)
        assertEquals(0, observations)
        assertFalse(file.exists())
    }

    @Test fun failedReceiptWritePublishesActualRunningStateWithoutInventingSuccess() = withFile { file ->
        val goodStore = FileToolTaskStore(file)
        var writes = 0
        val store = object : ToolTaskStore {
            override fun read() = goodStore.read()
            override fun update(change: (List<ToolTaskAttempt>) -> List<ToolTaskAttempt>): List<ToolTaskAttempt> {
                if (++writes == 3) throw ToolTaskStorageException()
                return goodStore.update(change)
            }
        }
        val ledger = ToolTaskLedger(store)
        val observed = mutableListOf<ToolTaskState>()
        var calls = 0
        val result = JournaledActionPipeline(ledger, MobileActionExecutor {
            calls++
            ExecutionResult(true, "effect")
        }, onJournalChanged = { observed += ledger.snapshot().single().state }).execute(ActionRequest("read_battery"))

        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertEquals(listOf(ToolTaskState.RUNNING, ToolTaskState.RUNNING), observed)
        assertEquals(1, calls)
    }
}
