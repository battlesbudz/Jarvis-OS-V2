package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * Finding 1 (reminder delivery) regression test: the real
 * create -> alarm -> claim -> engine-dispatch -> complete path delivers
 * exactly one notification with a terminal receipt, and a redelivered
 * alarm claims nothing.
 *
 * Boundary notes: the Android alarm itself is faked through the
 * coordinator's injected scheduler lambda (the designed seam). The
 * receiver-side claim/complete flow is the real [WorkflowLedger]. The
 * notification effect runs through the real [JournaledActionPipeline]
 * (admission, journal, [MobileActionPipeline] validation) with a fake
 * [MobileActionExecutor] whose PostNotification success mirrors
 * [AndroidMobileActionExecutor]'s ("Posted the reminder notification.").
 */
class ReminderDeliveryTest {

    @Test fun reminderDeliversExactlyOnceWithTerminalReceipt() {
        var nowMs = 1_700_000_000_000L
        val store = InMemoryToolTaskStore()
        val ledger = WorkflowLedger(store, now = { nowMs })
        val armed = mutableListOf<WorkflowOccurrence>()
        val scheduler: (WorkflowOccurrence) -> WorkflowAlarmScheduler.Scheduled = { occurrence ->
            armed += occurrence
            WorkflowAlarmScheduler.Scheduled(WorkflowScheduling.AlarmMode.EXACT, null)
        }
        val coordinator = ReminderCoordinator(ledger, scheduler, now = { nowMs })

        val message = "Take out the trash"
        val created = coordinator.createReminder(message, nowMs + 3_600_000)
        assertTrue("createReminder must succeed: ${created.message}", created.succeeded)

        val workflowId = ledger.list().single().id
        val occurrences = ledger.occurrencesFor(workflowId)
        assertEquals("exactly one scheduled occurrence", 1, occurrences.size)
        val occurrence = occurrences.single()
        assertEquals(WorkflowOccurrenceState.SCHEDULED, occurrence.state)
        assertEquals("the alarm is armed exactly once", 1, armed.size)
        assertEquals(occurrence.id, armed.single().id)

        // The alarm fires: claim atomically, then run the real definition.
        nowMs += 3_600_000
        val claimed = ledger.claimDueOccurrence(occurrence.id)
        assertNotNull("a due occurrence must be claimable", claimed)
        assertNull("a duplicate claim must not re-fire", ledger.claimDueOccurrence(occurrence.id))

        val posted = mutableListOf<Pair<String, String>>()
        val executor = MobileActionExecutor { action ->
            when (action) {
                is MobileAction.PostNotification -> {
                    posted += action.title to action.text
                    // Mirrors AndroidMobileActionExecutor's PostNotification success shape.
                    ExecutionResult(true, "Posted the reminder notification.")
                }
                else -> fail("unexpected action dispatched: $action")
            }
        }
        val taskLedger = ToolTaskLedger(store)
        val pipeline = JournaledActionPipeline(taskLedger, executor)
        val definition = ledger.definitionFor(checkNotNull(claimed))!!
        val outcome = WorkflowEngine(now = { nowMs }).run(definition, dispatch = { request ->
            pipeline.execute(request)
        })
        assertTrue("the engine run must complete: $outcome",
            outcome is WorkflowRunOutcome.Completed && outcome.succeeded)
        assertEquals("exactly one notification", 1, posted.size)
        assertEquals("Reminder", posted.single().first)
        assertEquals(message, posted.single().second)
        // The dispatch went through the journaled pipeline: one admitted
        // attempt, terminal with its receipt.
        assertEquals(ToolTaskState.SUCCEEDED, taskLedger.snapshot().single().state)

        // Terminal receipt: completing the occurrence is the receiver's
        // terminal step, and a redelivered alarm then claims nothing, so a
        // second engine run can never double-post.
        assertTrue(ledger.completeOccurrence(occurrence.id, true, "Posted: $message"))
        assertEquals(WorkflowOccurrenceState.SUCCEEDED, ledger.occurrence(occurrence.id)!!.state)
        assertNull("a redelivered alarm must claim nothing", ledger.claimDueOccurrence(occurrence.id))
        assertEquals("no second delivery", 1, posted.size)

        val receipt = ledger.receiptsFor(workflowId).single { it.kind == WorkflowReceiptKind.COMPLETED }
        assertTrue("the completion receipt names the reminder text: ${receipt.message}",
            receipt.message.contains(message))
    }
}
