package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.junit.Assert.*
import org.junit.Test

/** Structured-output fixtures exercise the real decoder and validator against a small test world.
 * These are contract tests, not evidence that a language model generated the right call. */
class NativeToolJourneyTest {
    private class World : MobileActionExecutor {
        var volume = 20
        val executed = mutableListOf<MobileAction>()
        override fun execute(action: MobileAction): ExecutionResult {
            executed += action
            return when (action) {
                MobileAction.ReadBattery -> ExecutionResult(true, "Battery is at 73 percent.")
                is MobileAction.SetVolume -> {
                    volume = action.level
                    ExecutionResult(true, "Volume applied")
                }
                is MobileAction.OpenApp -> ExecutionResult(false, "App is unavailable")
            }
        }
    }

    private fun execute(world: World, call: ToolCall): ExecutionResult? =
        NativeActionDecoder.decode(call)?.let { MobileActionPipeline(executor = world).execute(it) }

    @Test fun decodedToolSequenceProducesObservableState() {
        val world = World()
        assertTrue(execute(world, ToolCall("set_volume", """{"args":{"level":40}}"""))!!.succeeded)
        assertEquals(40, world.volume)
        assertEquals("Battery is at 73 percent.", execute(world, ToolCall("read_battery", "{}"))!!.message)
        assertEquals(listOf(MobileAction.SetVolume(40), MobileAction.ReadBattery), world.executed)
    }

    @Test fun malformedUnsupportedAndInvalidCallsHaveNoSideEffects() {
        val world = World()
        assertNull(execute(world, ToolCall("set_volume", "not json")))
        assertNull(execute(world, ToolCall("delete_everything", "{}")))
        assertFalse(execute(world, ToolCall("set_volume", """{"level":101}"""))!!.succeeded)
        assertFalse(execute(world, ToolCall("open_app", "{}"))!!.succeeded)
        assertEquals(20, world.volume)
        assertTrue(world.executed.isEmpty())
    }

    @Test fun stringWrappedArgumentsUseTheSameValidation() {
        val world = World()
        assertTrue(execute(world, ToolCall("set_volume", """{"args":"{\"level\":\"50%\"}"}"""))!!.succeeded)
        assertEquals(50, world.volume)
    }

    @Test fun executorFailureIsNotReportedAsSuccess() {
        val world = World()
        val result = execute(world, ToolCall("open_app", """{"app_name":"Missing app","package_name":"org.example.missing"}"""))!!
        assertFalse(result.succeeded)
        assertEquals("App is unavailable", result.message)
        assertEquals(listOf(MobileAction.OpenApp("Missing app", "org.example.missing")), world.executed)
    }
}
