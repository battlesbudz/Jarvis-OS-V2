package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient
import com.battlesbudz.jarvis.v2.ai.TurnKind
import com.battlesbudz.jarvis.v2.ai.TurnOrchestrator
import com.battlesbudz.jarvis.v2.ai.TurnPlan
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationContextPreparationTest {
    private class Memory : ConversationMemoryAccess {
        override val deliveryFence = MemoryDeliveryFence()
        var available = true
        var changed = false
        var cutoff = false
        var tokenCleared = false
        override fun approvedSnapshot(query: String, maxChars: Int): MemoryTurnContext? =
            if (available) MemoryTurnContext("approved packet", "token", query, null, 1,
                hasApprovedMemories = true, currentEpoch = { 1 }) else null
        override fun adopt(context: MemoryTurnContext) = changed
        override fun clearNativeToken() { tokenCleared = true }
        override fun isCurrent(context: MemoryTurnContext) = context.isCurrent()
        override fun consumeHistoryCutoff() = cutoff.also { cutoff = false }
        override fun takeCaptureReceipt(prompt: String): ConversationMemoryResult? = null
    }
    private class Fixture {
        val memory = Memory()
        var currentHistory = listOf(ChatEntry("You", "new topic"))
        val terminal = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val bound = mutableListOf<MemoryDeliveryFence.Ticket>()
        val state = object : ConversationSessionState {
            override var engine: LiteRtLmEngine? = null
            override var hasContext = true
            override var characters = 800
        }
        val model = LocalModelSpec("test", "test.litertlm", recommendedGpu = false)
        val context = ShortTermConversationContext()
        val models = ConversationModelSession(state, { model }, { true }, { }, { true }, { "unused" },
            "unused", context, {})
        val references = object : ConversationReferences {
            override suspend fun fetch(query: String): String? { queries.add(query); return "reference evidence" }
            override fun isInsufficientAnswer(answer: String) = answer.isBlank()
        }
        val reply = ConversationReply("context", PipelineBenchmarkCapture("context", "text", 0,
            PipelineBenchmarkProvenance("test", 1)), true, memory.deliveryFence,
            { it() }, {}, terminal::add, {}, {}, { _, _, _ -> })
        val prompt = ConversationPrompt(ConversationPromptBuilder(context), false, { null }, { reply.memoryContext })
        val stage = ConversationContextPreparation({ currentHistory }, memory,
            TurnOrchestrator(ReferenceGroundingClient()), models, references, ConversationDiagnostics({}, {}, {}, {}))
        suspend fun prepare(text: String, plan: TurnPlan = TurnPlan(TurnKind.NORMAL_CHAT)) = stage.prepare(
            ConversationTurnRequest(text, emptyList(), "conversation", false, false, false,
                false, false, null, null, false),
            RoutedConversation(listOf(ChatEntry("You", "old retained context")), plan, ActionTurnPlan.NotAction, null),
            reply, prompt, 10_000, { ticket, _ -> bound.add(ticket) })
    }

    @Test fun failedApprovedReadFencesResidentMemoryAndStopsBeforeLookup() = runBlocking {
        val f = Fixture()
        val prior = f.memory.deliveryFence.ticket()
        f.memory.available = false
        assertNull(f.prepare("A normal request", TurnPlan(TurnKind.EXPLICIT_LOOKUP, "lookup")))
        assertFalse(f.memory.deliveryFence.isValid(prior))
        assertTrue(f.memory.tokenCleared)
        assertFalse(f.state.hasContext)
        assertEquals(0, f.state.characters)
        assertTrue(f.queries.isEmpty())
        assertTrue(f.bound.isEmpty())
        assertEquals(listOf("Memory context is unavailable right now; please try again after storage recovers."), f.terminal)
    }

    @Test fun adoptedMemoryReloadsPostCutoffHistoryBeforeBuildingPrompt() = runBlocking {
        val f = Fixture()
        f.memory.changed = true
        f.memory.cutoff = true
        val prepared = f.prepare("A normal request")!!
        assertEquals(f.currentHistory, prepared.history)
        assertFalse(prepared.history.any { it.text == "old retained context" })
        assertTrue(prepared.memoryHistoryInvalidated)
        assertEquals(1, f.bound.size)
        assertFalse(f.state.hasContext)
        assertTrue(f.prompt.build("A normal request", null, emptyList(), false).contains("approved packet"))
    }

    @Test fun personalRecallUsesApprovedLocalEvidenceWithoutExternalQuery() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare("What is my name?", TurnPlan(TurnKind.FACTUAL_LOCAL_FIRST, "external"))!!
        assertTrue(prepared.personalMemoryRecall)
        assertNull(prepared.referenceContext)
        assertTrue(f.queries.isEmpty())
        assertTrue(f.terminal.isEmpty())
    }

    @Test fun explicitLookupKeepsRequestedReferenceRouteWithApprovedMemoryPresent() = runBlocking {
        val f = Fixture()
        val prepared = f.prepare("Look up my name", TurnPlan(TurnKind.EXPLICIT_LOOKUP, "explicit query"))!!
        assertFalse(prepared.personalMemoryRecall)
        assertEquals(listOf("explicit query"), f.queries)
        assertEquals("reference evidence", prepared.referenceContext)
    }
}
