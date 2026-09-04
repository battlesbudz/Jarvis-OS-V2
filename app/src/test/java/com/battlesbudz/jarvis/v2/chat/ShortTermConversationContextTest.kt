package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortTermConversationContextTest {
    @Test
    fun promptContainsSummaryAndRecentTurns() {
        val context = ShortTermConversationContext()
        context.updateSummary("Earlier decision: keep the chat local.")
        val prompt = context.promptContext(
            listOf("You" to "old", "Jarvis" to "new")
        )

        assertTrue(prompt.contains("Earlier decision: keep the chat local."))
        assertTrue(prompt.contains("You: old"))
        assertTrue(prompt.contains("Jarvis: new"))
    }

    @Test
    fun compactionKeepsNewestTurnsAndDropsOldestOverflow() {
        val context = ShortTermConversationContext()
        val snapshot = context.compactSnapshot(
            listOf(
                "You" to "oldest ".repeat(700),
                "Jarvis" to "middle ".repeat(700),
                "You" to "newest turn"
            )
        )

        assertTrue(snapshot.contains("newest turn"))
        assertFalse(snapshot.contains("oldest oldest oldest oldest oldest"))
        assertTrue(snapshot.length <= 4_000)
    }

    @Test
    fun summaryIsBounded() {
        val context = ShortTermConversationContext()
        context.updateSummary("x".repeat(10_000))

        assertTrue(context.summaryForDiagnostics()!!.length <= 4_000)
    }
}
