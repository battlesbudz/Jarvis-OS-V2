package com.battlesbudz.jarvis.v2.chat

import org.junit.Assert.*
import org.junit.Test

class CompactVoiceContextTest {
    @Test fun onlyExactDuplicateAnchorIsRemoved() {
        val context = ShortTermConversationContext()
        val history = listOf("You" to "My dog is Kiko.", "Jarvis" to "Tell me about Kiko.")
        val compact = context.promptContext(history, compact = true)
        assertEquals(1, Regex(Regex.escape("My dog is Kiko.")).findAll(compact).count())
        assertTrue(compact.contains("Jarvis: Tell me about Kiko."))
        assertEquals(2, Regex(Regex.escape("My dog is Kiko.")).findAll(context.promptContext(history)).count())
    }

    @Test fun longAnchorRetainsInformationBeyondRecentTurnLimit() {
        val topic = "x".repeat(350) + " Important old detail."
        val compact = ShortTermConversationContext().promptContext(listOf("You" to topic), compact = true)
        assertTrue(compact.contains("Important old detail."))
    }

    @Test fun duplicateBeyondContextCapDoesNotRemoveVisibleAnchor() {
        val context = ShortTermConversationContext().apply { updateSummary("s".repeat(2_000)) }
        val history = listOf("You" to "Original dog topic.") +
            (1..7).map { "Jarvis" to "x".repeat(450) } + ("You" to "Original dog topic.")
        val compact = context.promptContext(history, compact = true)
        assertTrue(compact.contains("Earlier topic:\nYou: Original dog topic."))
        assertEquals(3_000, compact.length)
    }

    @Test fun oldAnchorOutsideRecentWindowAndSummaryArePreserved() {
        val context = ShortTermConversationContext().apply { updateSummary("Persistent summary detail.") }
        val history = listOf("You" to "Original dog topic.") + (1..10).map { "You" to "Recent request $it." }
        val compact = context.promptContext(history, compact = true)
        assertTrue(compact.contains("Original dog topic."))
        assertTrue(compact.contains("Persistent summary detail."))
        assertTrue(compact.contains("Recent request 10."))
    }
}
