package com.battlesbudz.jarvis.v2.presentation

import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentActivityTest {
    @Test fun onlyAnOwnedOperationPublishesActivityAndClosingItIsIdempotent() {
        val monitor = AgentActivityMonitor()
        assertNull(monitor.state.value)

        val lease = monitor.beginReferences("conversation-a")
        val active = requireNotNull(monitor.state.value)
        assertEquals("conversation-a", active.conversationId)
        assertEquals(AgentActivityKind.CHECKING_REFERENCES, active.kind)
        assertEquals("Checking references", active.label)

        lease.close()
        assertNull(monitor.state.value)
        lease.close()
        assertNull(monitor.state.value)
    }

    @Test fun nextAdmittedTurnClearsFailureAndOldExpiryCannotDismissNewWork() {
        val monitor = AgentActivityMonitor()
        val failure = monitor.beginFailure("conversation-a")
        assertEquals(AgentActivityKind.ERROR, monitor.state.value?.kind)
        assertEquals("Something went wrong", monitor.state.value?.label)
        monitor.clearFailure()
        assertNull(monitor.state.value)
        val current = monitor.beginReferences("conversation-a")
        val snapshot = monitor.state.value
        failure.close()
        monitor.clearFailure()
        assertEquals(snapshot, monitor.state.value)
        current.close()
        assertNull(monitor.state.value)
    }

    @Test fun staleCompletionCannotDismissOrRestoreAnotherOperation() {
        val monitor = AgentActivityMonitor()
        val old = monitor.beginReferences("conversation-a")
        val oldId = requireNotNull(monitor.state.value).operationId
        val current = monitor.beginReferences("conversation-b")
        val snapshot = requireNotNull(monitor.state.value)
        assertTrue(snapshot.operationId > oldId)

        old.close()
        assertEquals(snapshot, monitor.state.value)
        current.close()
        assertNull(monitor.state.value)
        old.close()
        assertNull(monitor.state.value)
    }

    @Test fun cancellationFinallyClearsOnlyTheCancelledOwnersLease() = runBlocking {
        val monitor = AgentActivityMonitor()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            monitor.beginReferences("conversation-a").use {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertNotNull(monitor.state.value)
        job.cancelAndJoin()
        assertNull(monitor.state.value)
    }

    @Test fun cancelledOldOwnerCannotClearANewerRead() = runBlocking {
        val monitor = AgentActivityMonitor()
        val entered = CompletableDeferred<Unit>()
        val oldJob = launch {
            monitor.beginReferences("conversation-a").use {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val current = monitor.beginReferences("conversation-b")
        val currentSnapshot = monitor.state.value
        oldJob.cancelAndJoin()
        assertEquals(currentSnapshot, monitor.state.value)
        current.close()
        assertNull(monitor.state.value)
    }

    @Test fun routingAndInsufficientAnswerChecksDoNotPretendToReadSources() {
        val monitor = AgentActivityMonitor()
        var started = 0
        val client = ReferenceGroundingClient(onReadStarted = {
            started++
            monitor.beginReferences("conversation-a")::close
        })

        assertFalse(client.shouldAutomaticallyLookup("Tell me a joke"))
        assertTrue(client.shouldAutomaticallyLookup("Who was Ada Lovelace?"))
        assertTrue(client.isInsufficientAnswer("I don't know"))
        assertNull(monitor.state.value)
        assertEquals(0, started)
    }

    @Test fun actualReadPublishesOnlyConstantMetadataAndAlwaysFinishes() = runBlocking {
        val monitor = AgentActivityMonitor()
        val observed = mutableListOf<AgentActivitySnapshot>()
        val client = ReferenceGroundingClient(
            readBytes = {
                observed += requireNotNull(monitor.state.value)
                "%PDF-fixture".toByteArray()
            },
            onReadStarted = { monitor.beginReferences("conversation-a")::close },
            pdfText = { "A document about apricot trees." },
        )

        val result = client.fetchIfRequested("https://example.org/private-reference.pdf")
        assertTrue(requireNotNull(result).context.contains("apricot trees"))
        assertEquals(1, observed.size)
        assertEquals("conversation-a", observed.single().conversationId)
        assertEquals("Checking references", observed.single().label)
        assertEquals(AgentActivityKind.CHECKING_REFERENCES, observed.single().kind)
        assertFalse(observed.single().toString().contains("private-reference"))
        assertNull(monitor.state.value)
    }

    @Test fun failedReadClearsActivityWithoutTurningEmptyEvidenceIntoSuccess() = runBlocking {
        val monitor = AgentActivityMonitor()
        var attempted = 0
        var finished = 0
        val client = ReferenceGroundingClient(
            readBytes = {
                attempted++
                assertNotNull(monitor.state.value)
                throw IOException("fixture read failure")
            },
            onReadStarted = {
                val lease = monitor.beginReferences("conversation-a")
                val finish: () -> Unit = { lease.close(); finished++ }
                finish
            },
        )

        assertNull(client.fetchIfRequested("Who was Ada Lovelace?"))
        assertTrue(attempted > 0)
        assertEquals(attempted, finished)
        assertNull(monitor.state.value)
    }

    @Test fun oldReadFinallyCannotClearANewerOperation() = runBlocking {
        val monitor = AgentActivityMonitor()
        var newer: AgentActivityMonitor.Lease? = null
        var newerSnapshot: AgentActivitySnapshot? = null
        val client = ReferenceGroundingClient(
            readBytes = {
                newer = monitor.beginReferences("conversation-b")
                newerSnapshot = monitor.state.value
                "%PDF-fixture".toByteArray()
            },
            onReadStarted = { monitor.beginReferences("conversation-a")::close },
            pdfText = { "A document about apricot trees." },
        )

        assertNotNull(client.fetchIfRequested("https://example.org/reference.pdf"))
        assertEquals(newerSnapshot, monitor.state.value)
        requireNotNull(newer).close()
        assertNull(monitor.state.value)
    }

    @Test fun observerFailuresCannotChangeTheRetrievedEvidence() = runBlocking {
        for (failAtStart in listOf(true, false)) {
            val client = ReferenceGroundingClient(
                readBytes = { "%PDF-fixture".toByteArray() },
                onReadStarted = {
                    if (failAtStart) error("fixture observer start failure")
                    val finish: () -> Unit = { error("fixture observer finish failure") }
                    finish
                },
                pdfText = { "A document about apricot trees." },
            )
            assertTrue(requireNotNull(client.fetchIfRequested("https://example.org/reference.pdf"))
                .context.contains("apricot trees"))
        }
    }
}
