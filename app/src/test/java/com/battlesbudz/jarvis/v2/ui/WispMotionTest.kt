package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.presentation.AgentActivityKind
import com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot
import org.junit.Assert.*
import org.junit.Test

class WispMotionTest {
    private fun work(id: Long, conversation: String = "chat", kind: AgentActivityKind = AgentActivityKind.WORKING) =
        AgentActivitySnapshot(id, conversation, kind, "Preparing your request")

    @Test fun selectedMouthGrowsOnlyByApprovedDimensions() {
        assertEquals(2f, WispMotion.mouthWidthScale, 0f)
        assertEquals(1.65f, WispMotion.mouthHeightScale, 0f)
        assertEquals(1.5f, WispMotion.smileHeightScale, 0f)
    }

    @Test fun reducedMotionFreezesNodBounceAndMouthForEveryActivity() {
        for (activity in WispActivity.entries) {
            assertEquals(0f, WispMotion.bounce(activity, .3f, moving = false), 0f)
            assertEquals(0f, WispMotion.mouthLevel(activity, .8f, moving = false), 0f)
        }
        assertEquals(0f, WispMotion.nod(.3f, moving = false), 0f)
    }

    @Test fun oneShotGesturesCannotBecomeIndefiniteProgressLoops() {
        assertTrue(WispMotion.bounce(WispActivity.SUCCESS, .36f, true) < -2.9f)
        assertTrue(WispMotion.nod(.325f, true) > 3.9f)
        for (age in listOf(-1f, 0f, 1f, 30f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, WispMotion.bounce(WispActivity.SUCCESS, age, true), 0f)
            assertEquals(0f, WispMotion.nod(age, true), 0f)
        }
        for (activity in WispActivity.entries.filter { it != WispActivity.SUCCESS })
            assertEquals(activity.name, 0f, WispMotion.bounce(activity, .36f, true), 0f)
        assertEquals(0f, WispMotion.nod(null, true), 0f)
    }

    @Test fun onlyObservedPlaybackCanOpenMouthAndInterruptionClosesImmediately() {
        assertEquals(.7f, WispMotion.mouthLevel(WispActivity.SPEAKING, .7f, true), 0f)
        assertEquals(1f, WispMotion.mouthLevel(WispActivity.SPEAKING, 2f, true), 0f)
        for (level in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(0f, WispMotion.mouthLevel(WispActivity.SPEAKING, level, true), 0f)
        for (activity in WispActivity.entries.filter { it != WispActivity.SPEAKING })
            assertEquals(activity.name, 0f, WispMotion.mouthLevel(activity, .9f, true), 0f)
        assertEquals(0f, WispMotion.mouthLevel(null, .9f, true), 0f)
        // The new speech attempt can animate again without any body/task transition.
        assertEquals(.4f, WispMotion.mouthLevel(WispActivity.SPEAKING, .4f, true), 0f)
    }

    @Test fun receiptNodRequiresNewAdmittedWorkAndNeverHistoricalMount() {
        val tracker = WispReceptionTracker()
        assertNull(tracker.update(work(1), "chat"))
        assertNull(tracker.update(work(1), "chat"))
        assertEquals(2L, tracker.update(work(2), "chat"))
        assertNull(tracker.update(work(2).copy(label = "Writing your reply"), "chat"))
        assertNull(WispReceptionTracker().update(work(2), "chat"))
    }

    @Test fun foregroundReadsFailuresAndOlderTurnsCannotReplayReception() {
        val tracker = WispReceptionTracker()
        assertNull(tracker.update(null, "chat"))
        assertEquals(10L, tracker.update(work(10), "chat"))
        assertNull(tracker.update(work(11, kind = AgentActivityKind.CHECKING_REFERENCES), "chat"))
        assertNull(tracker.update(work(10), "chat"))
        assertNull(tracker.update(work(12, kind = AgentActivityKind.ERROR), "chat"))
        assertNull(tracker.update(null, "chat"))
        assertNull(tracker.update(work(9), "chat"))
        assertEquals(13L, tracker.update(work(13), "chat"))
    }

    @Test fun conversationSwitchAndForeignObservationDoNotAcknowledgeAnotherRequest() {
        val tracker = WispReceptionTracker()
        assertNull(tracker.update(null, "chat"))
        assertNull(tracker.update(work(100, "foreign"), "chat"))
        assertEquals(1L, tracker.update(work(1), "chat"))
        assertNull(tracker.update(work(100, "foreign"), "foreign"))
        assertNull(tracker.update(work(1), "chat"))
        assertEquals(2L, tracker.update(work(2), "chat"))
    }
    @Test fun newOneShotsWhileStoppedOrReducedMotionNeverReplayAfterResume() {
        // Both new terminal receipts and reception keys create the same one-shot age owner.
        repeat(2) {
            val bornStopped = WispOneShotAge(activeAtCreation = false)
            bornStopped.advance(.3f)
            assertEquals(0f, WispMotion.nod(bornStopped.age, true), 0f)
            assertEquals(0f, WispMotion.bounce(WispActivity.SUCCESS, bornStopped.age, true), 0f)
            val active = WispOneShotAge(activeAtCreation = true)
            active.advance(.2f)
            assertTrue(WispMotion.nod(active.age, true) > 0f)
            active.consume()
            active.advance(.2f)
            assertEquals(0f, WispMotion.nod(active.age, true), 0f)
            assertEquals(0f, WispMotion.bounce(WispActivity.SUCCESS, active.age, true), 0f)
            val genuinelyNew = WispOneShotAge(activeAtCreation = true)
            genuinelyNew.advance(.3f)
            assertTrue(WispMotion.nod(genuinelyNew.age, true) > 0f)
        }
    }

    @Test fun lifecycleResumeBaselinesLiveWorkBeforeCollectedReplay() {
        val tracker = WispReceptionTracker()
        assertNull(tracker.update(null, "chat"))
        assertEquals(1L, tracker.update(work(1), "chat"))
        tracker.suspend()
        assertNull(tracker.update(work(2), "chat"))
        tracker.resume(work(3), "chat")
        assertNull(tracker.update(work(1), "chat"))
        assertNull(tracker.update(work(2), "chat"))
        assertNull(tracker.update(work(3), "chat"))
        assertEquals(4L, tracker.update(work(4), "chat"))
        tracker.suspend()
        tracker.resume(work(10, "other"), "other")
        assertNull(tracker.update(work(10, "other"), "other"))
    }

    @Test fun resumeDuringReferenceReadCannotNodWhenItsOlderParentReturns() {
        val tracker = WispReceptionTracker()
        tracker.update(work(1), "chat")
        tracker.suspend()
        tracker.resume(work(5, kind = AgentActivityKind.CHECKING_REFERENCES), "chat")
        assertNull(tracker.update(work(5, kind = AgentActivityKind.CHECKING_REFERENCES), "chat"))
        assertNull(tracker.update(work(4), "chat"))
        tracker.suspend()
        tracker.resume(null, "chat")
        assertNull(tracker.update(work(4), "chat"))
        assertEquals(6L, tracker.update(work(6), "chat"))
    }

}
