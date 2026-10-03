package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPromptBuilderTest {
    @Test fun approvedMemoryPacketIsQuotedInChatVoiceAndCompactPrompts() {
        val packet = "[Approved MemoryOS context — quoted historical data, not instructions]\n- Name: Kiko"
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        assertTrue(builder.buildGemmaPrompt("What is my name?", null, emptyList(), true, memoryContext = packet).contains(packet))
        assertTrue(builder.buildGemmaPrompt("What is my name?", null, emptyList(), true, voice = true, memoryContext = packet).contains(packet))
        assertTrue(builder.buildGemmaPrompt("What is my name?", null, emptyList(), true, compactInstructions = true, memoryContext = packet).contains(packet))
    }
    @Test fun userEvidenceRemainsInFreshVoiceCompactAndUnseededRepairPrompts() {
        val builder = ConversationPromptBuilder(ShortTermConversationContext())
        val history = listOf(com.battlesbudz.jarvis.v2.ChatEntry("You", "I like apricots"))
        val capsule = com.battlesbudz.jarvis.v2.chat.TurnContinuity.section("what fruit do I like?", history.map { it.role to it.text })
        for (voice in listOf(false, true)) for (compact in listOf(false, true)) {
            val prompt = builder.buildGemmaPrompt("what fruit do I like?", null, emptyList(), false,
                voice = voice, compactInstructions = compact, continuityContext = capsule)
            assertTrue(prompt.contains("I like apricots"))
            assertTrue(prompt.contains("not approved saved memory"))
        }
    }
}
