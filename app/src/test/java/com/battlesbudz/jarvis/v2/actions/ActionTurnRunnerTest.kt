package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class ActionTurnRunnerTest {
    private class World(private val failAt: Int? = null) : MobileActionExecutor {
        val actions = mutableListOf<MobileAction>()
        override fun execute(action: MobileAction): ExecutionResult {
            actions += action
            return if (actions.size == failAt) ExecutionResult(false, "failed") else ExecutionResult(true, "did $action")
        }
    }
    private fun call(name: String, json: String) = ToolCall(name, json)

    @Test fun batchAndSequentialCallsFollowTheAuthorizedOrder() {
        val mediaPlan = ActionTurnPlan.parse("Set my media volume to 30 percent, then tell me my battery level")
        assertEquals(ActionTurnPlan.Ready(listOf(
            ActionTurnPlan.Step(ActionRequest("set_volume", mapOf("level" to "30")), "Set my media volume to 30 percent"),
            ActionTurnPlan.Step(ActionRequest("read_battery"), "tell me my battery level"))), mediaPlan)
        val plan = ActionTurnPlan.parse("Read battery, then set volume to 20 percent, and then open Settings")
        val world = World()
        val outcome = ActionTurnRunner(world).run(plan, listOf(
            listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}")),
            listOf(call("open_app", "{\"app\":\"Settings\"}"))))
        assertTrue(outcome.completed)
        assertEquals(listOf(MobileAction.ReadBattery, MobileAction.SetVolume(20), MobileAction.OpenApp("Settings")), world.actions)
    }

    @Test fun malformedWrongOrOutOfOrderResponseHasNoEffects() {
        val plan = ActionTurnPlan.parse("Read battery then set volume to 20 percent")
        for (calls in listOf(
            listOf(call("set_volume", "{\"level\":20}")),
            listOf(call("read_battery", "{\"extra\":true}")),
            listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":100}")))) {
            val world = World()
            assertFalse(ActionTurnRunner(world).run(plan, listOf(calls)).completed)
            assertTrue(world.actions.isEmpty())
        }
    }

    @Test fun explicitRepeatsExecuteButModelReplaysDoNot() {
        val repeated = ActionTurnPlan.parse("Set volume to 20 percent then set volume to 20 percent")
        val world = World()
        assertTrue(ActionTurnRunner(world).run(repeated, listOf(listOf(
            call("set_volume", "{\"level\":20}"), call("set_volume", "{\"level\":20}")))).completed)
        assertEquals(2, world.actions.size)
        val once = ActionTurnPlan.parse("Set volume to 20 percent then read battery")
        val dedup = World()
        val result = ActionTurnRunner(dedup).run(once, listOf(
            listOf(call("set_volume", "{\"level\":20}")),
            listOf(call("set_volume", "{\"level\":20}"), call("read_battery", "{}"))))
        assertTrue(result.completed)
        assertEquals(listOf(MobileAction.SetVolume(20), MobileAction.ReadBattery), dedup.actions)
    }

    @Test fun invalidPlansAndFailureStopWithoutLaterEffects() {
        assertTrue(ActionTurnPlan.parse("Set volume to 20 percent and explain batteries") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 5000 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set media volume to -30 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 30.5 percent") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Set volume to 20 percent and explain batteries") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("What battery technology is used?") is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Tell me about battery technology") is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Set volume to 30 percent delete files") is ActionTurnPlan.Rejected)
        assertTrue(ActionTurnPlan.parse("Read battery then set volume to 20 percent then open Settings then open YouTube") is ActionTurnPlan.Rejected)
        val world = World(failAt = 2)
        val result = ActionTurnRunner(world).run(ActionTurnPlan.parse("Read battery then set volume to 20 percent then open Settings"),
            listOf(listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}"), call("open_app", "{\"app\":\"Settings\"}"))))
        assertFalse(result.completed)
        assertEquals(2, world.actions.size)
    }
    @Test fun cancellationAndRetryCapNeverReplayCompletedEffects() {
        val cancelled = World()
        try {
            ActionTurnRunner(cancelled).run(ActionTurnPlan.parse("Read battery then set volume to 20 percent"), listOf(
                listOf(call("read_battery", "{}")), emptyList()))
        } catch (_: java.util.concurrent.CancellationException) { }
        // Duplicate-only model passes terminate at the bounded coordinator limit without replaying volume.
        val world = World()
        val result = ActionTurnRunner(world).run(ActionTurnPlan.parse("Set volume to 20 percent then read battery"),
            List(7) { listOf(call("set_volume", "{\"level\":20}")) })
        assertFalse(result.completed)
        assertEquals(listOf(MobileAction.SetVolume(20)), world.actions)
    }
    @Test fun exactNumberWordsAndOrdinaryConversationCannotAuthorizeEffects() {
        for (text in listOf("Set volume to two hundred percent", "Set volume to negative thirty percent",
            "Set volume to thirty banana percent", "Set volume to 30banana percent")) {
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.Rejected)
        }
        for (text in listOf("What happens when water freezes?", "I like cats and dogs and birds and fish", "Make a poem about the sea"))
            assertTrue(text, ActionTurnPlan.parse(text) is ActionTurnPlan.NotAction)
        assertEquals(ActionTurnPlan.Ready(listOf(ActionTurnPlan.Step(ActionRequest("set_volume", mapOf("level" to "31")), "Set volume to thirty one percent"))),
            ActionTurnPlan.parse("Set volume to thirty one percent"))
        for (word in listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine"))
            assertTrue(word, ActionTurnPlan.parse("Set volume to $word percent") is ActionTurnPlan.Ready)
    }

    @Test fun actualCoroutineCancellationAfterReceiptSkipsLaterAction() = kotlinx.coroutines.runBlocking {
        val executed = mutableListOf<MobileAction>()
        val runner = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") })
        val job = kotlinx.coroutines.Job()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.CoroutineScope(job).launch {
            try {
                runner.runNative(ActionTurnPlan.parse("Read battery then set volume to 20 percent"),
                    listOf(call("read_battery", "{}"), call("set_volume", "{\"level\":20}")),
                    dispatch = { request ->
                        executed += MobileActionValidator().validate(request).let { (it as ActionValidation.Valid).action }
                        if (executed.size == 1) coroutineContext.cancel()
                        ExecutionResult(true, "completed ${request.name}")
                    }, nextCalls = { emptyList() })
            } catch (_: java.util.concurrent.CancellationException) { } finally { done.complete(Unit) }
        }
        done.await()
        assertEquals(listOf(MobileAction.ReadBattery), executed)
    }

    @Test fun authoritativePlanKeepsImmediateOffersButRejectsStaleHistory() {
        val offer = listOf(com.battlesbudz.jarvis.v2.ChatEntry("Jarvis", "Shall I open YouTube?"))
        val yesPlan = ActionTurnPlan.parse("Yes", offer) as ActionTurnPlan.Ready
        assertEquals(ActionRequest("open_app", mapOf("app" to "YouTube")), yesPlan.steps.single().request)
        assertTrue(com.battlesbudz.jarvis.v2.voice.FinalVoiceToolGuard.allows(yesPlan.steps.single().sourceClause,
            "open_app", yesPlan.steps.single().request.arguments))
        assertEquals(ActionTurnPlan.Ready(listOf(ActionTurnPlan.Step(ActionRequest("open_app", mapOf("app" to "YouTube")), "Open YouTube"))),
            ActionTurnPlan.parse("Open it", offer))
        assertTrue(ActionTurnPlan.parse("Yes", listOf(com.battlesbudz.jarvis.v2.ChatEntry("You", "Open YouTube"),
            com.battlesbudz.jarvis.v2.ChatEntry("Jarvis", "What would you like to discuss?"))) is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("Read battery; set volume to 30 percent please; open Settings") is ActionTurnPlan.Ready)
    }

    @Test fun actualCancellationBeforeFirstEffectHasNoReceipt() = kotlinx.coroutines.runBlocking {
        val executed = mutableListOf<MobileAction>()
        val runner = ActionTurnRunner(MobileActionExecutor { ExecutionResult(true, "unused") })
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Job().apply { cancel() }) {
                runner.runNative(ActionTurnPlan.parse("Read battery"), listOf(call("read_battery", "{}")),
                    dispatch = { executed += MobileAction.ReadBattery; ExecutionResult(true, "done") }, nextCalls = { emptyList() })
            }
        } catch (_: java.util.concurrent.CancellationException) { }
        assertTrue(executed.isEmpty())
    }

}
