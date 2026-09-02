package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileActionPipelineTest {
    @Test
    fun naturalLanguageShapedRequestIsValidatedBeforeExecution() {
        val executed = mutableListOf<MobileAction>()
        val pipeline = MobileActionPipeline { action ->
            executed += action
            ExecutionResult(true, "Action executed")
        }

        // This represents the structured request emitted by MobileActions-270M.
        val result = pipeline.execute(
            ActionRequest("set_volume", mapOf("level" to "50"))
        )

        assertTrue(result.succeeded)
        assertEquals(listOf(MobileAction.SetVolume(50)), executed)
    }

    @Test
    fun invalidModelOutputNeverReachesExecutor() {
        var executorCalled = false
        val pipeline = MobileActionPipeline { 
            executorCalled = true
            ExecutionResult(true, "Unexpected")
        }

        val result = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "500")))

        assertTrue(!result.succeeded)
        assertTrue(!executorCalled)
    }
}