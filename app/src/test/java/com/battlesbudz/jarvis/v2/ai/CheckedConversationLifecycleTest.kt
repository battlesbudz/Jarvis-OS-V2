package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Audio's native quarantine/history-rebuild guard, ported verbatim.
 * Verifies the guard semantics the conversation session relies on:
 * a failed native drain quarantines the engine (no reuse until restart)
 * and a failed turn requires a confirmed history rebuild.
 */
class CheckedConversationLifecycleTest {
    private class FakeConversation {
        var idle = true
        var closed = false
        var idleFailure: Throwable? = null
        fun awaitIdle() { idleFailure?.let { throw it } }
    }

    private class Fixture {
        val quarantined = mutableListOf<Throwable>()
        val lifecycle = CheckedConversationLifecycle(
            createConversation = ::FakeConversation,
            cancelConversation = {},
            awaitIdle = { it.awaitIdle() },
            closeConversation = { it.closed = true },
            closeEngine = {},
            checkWorkerThread = {},
            onQuarantined = { quarantined.add(it) }
        )
    }

    @Test fun failedDrainQuarantinesEngineAndBlocksReuse() {
        val f = Fixture()
        f.lifecycle.initialize {}
        val turn = f.lifecycle.beginTurn()
        turn.conversation.idleFailure = IllegalStateException("native drain failed")
        try {
            f.lifecycle.finishTurn(turn, successful = { true })
            fail("a failed drain must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("native drain failed", expected.message)
        }
        assertTrue(f.lifecycle.isQuarantined)
        assertTrue(f.lifecycle.requiresConfirmedHistoryRebuild)
        assertEquals(1, f.quarantined.size)
        // No reuse while quarantined: the session's check must fail.
        try {
            f.lifecycle.beginTurn()
            fail("a quarantined engine must not begin turns")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("quarantin", ignoreCase = true) ||
                expected.message!!.contains("drain", ignoreCase = true))
        }
    }

    @Test fun failedTurnRequiresConfirmedHistoryRebuildWithoutQuarantine() {
        val f = Fixture()
        f.lifecycle.initialize {}
        val turn = f.lifecycle.beginTurn()
        val accepted = f.lifecycle.finishTurn(turn, successful = { false })
        assertFalse(accepted)
        assertFalse("a cancelled turn is not a quarantine", f.lifecycle.isQuarantined)
        assertTrue(f.lifecycle.requiresConfirmedHistoryRebuild)
        assertTrue("a failed turn's handle is never reused", turn.conversation.closed)
        // beginTurn refuses until the history is rebuilt from confirmed state.
        try {
            f.lifecycle.beginTurn()
            fail("must rebuild history before the next turn")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("history", ignoreCase = true))
        }
        f.lifecycle.resetForConfirmedHistory()
        assertFalse(f.lifecycle.requiresConfirmedHistoryRebuild)
        // A fresh turn works after the confirmed rebuild.
        val next = f.lifecycle.beginTurn()
        assertTrue(f.lifecycle.finishTurn(next, successful = { true }))
    }

    @Test fun successfulTurnDrainsAndReleases() {
        val f = Fixture()
        f.lifecycle.initialize {}
        val turn = f.lifecycle.beginTurn()
        var drained: FakeConversation? = null
        assertTrue(f.lifecycle.finishTurn(turn, successful = { true }, onDrained = { drained = it }))
        assertSame(turn.conversation, drained)
        assertFalse(f.lifecycle.isQuarantined)
        assertFalse(f.lifecycle.requiresConfirmedHistoryRebuild)
        assertTrue(f.quarantined.isEmpty())
    }

    @Test fun quarantineObserverReceivesDrainFailure() {
        val f = Fixture()
        f.lifecycle.initialize {}
        val turn = f.lifecycle.beginTurn()
        val drainFailure = IllegalStateException("boom")
        turn.conversation.idleFailure = drainFailure
        assertThrows(IllegalStateException::class.java) {
            f.lifecycle.finishTurn(turn, successful = { true })
        }
        assertEquals(1, f.quarantined.size)
        assertSame(drainFailure, f.quarantined.single())
    }
}
