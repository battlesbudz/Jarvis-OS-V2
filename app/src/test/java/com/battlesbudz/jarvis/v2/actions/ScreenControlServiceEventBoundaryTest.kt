package com.battlesbudz.jarvis.v2.actions

import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding 3 residual: real accessibility-service coverage of the event
 * boundary. Drives the actual [ScreenControlService.onAccessibilityEvent]
 * with genuine [AccessibilityEvent] instances and asserts how the shared
 * session treats them: another package's window-state change invalidates
 * the observation, Jarvis's own overlay windows are handled explicitly and
 * never invalidate, content-change events never invalidate by themselves
 * (dispatch-time content-generation verification covers them), and touch
 * events still feed the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenControlServiceEventBoundaryTest {

    private lateinit var service: ScreenControlService
    private val session get() = ScreenControlService.sharedSession

    @Before fun setUp() {
        service = Robolectric.buildService(ScreenControlService::class.java).create().get()
        session.release()
        session.admit("event-boundary", userApproved = true)
    }

    @After fun tearDown() {
        session.release()
    }

    private fun event(type: Int, packageName: String?): AccessibilityEvent =
        AccessibilityEvent.obtain().apply {
            eventType = type
            setPackageName(packageName)
        }

    private fun observedToken(): String {
        val obs = ScreenObservation(
            packageName = "com.other.app",
            nodes = listOf(
                ScreenNode("n0", "OK", "button", "100,400-300,460", clickable = true,
                    viewId = "com.other.app:id/ok", windowIdentity = "com.other.app#1")
            ),
            windowIdentity = "com.other.app#1"
        )
        return session.recordObservation(obs)
    }

    private fun isStale(token: String): Boolean =
        session.verifyTarget(
            "n0", token, "com.other.app#1",
            contentFingerprintOf(
                listOf(
                    ScreenNode("n0", "OK", "button", "100,400-300,460", clickable = true,
                        viewId = "com.other.app:id/ok", windowIdentity = "com.other.app#1")
                )
            )
        ) { null } is TargetVerification.Rejected

    @Test fun otherPackageWindowStateChangeInvalidatesObservation() {
        val token = observedToken()
        assertFalse("fresh observation must verify", isStale(token))
        service.onAccessibilityEvent(
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.other.app")
        )
        assertTrue("another package's window change must invalidate the observation", isStale(token))
        assertTrue("invalidation must not release the grant", session.isAdmitted)
    }

    @Test fun ownOverlayWindowEventsNeverInvalidate() {
        val token = observedToken()
        val own = service.packageName
        // Jarvis's own stop overlay / approval windows share the app
        // package: their window and content events are handled explicitly
        // and must neither invalidate the observation nor inherit approvals.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, own))
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, own))
        assertFalse("own overlay window events must not invalidate the observation", isStale(token))
        assertTrue(session.isAdmitted)
    }

    @Test fun contentChangesNeverInvalidateByThemselves() {
        val token = observedToken()
        // Content-change events are deliberately not invalidated here: they
        // are too noisy, and dispatch-time content-generation verification
        // already covers stale content.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.other.app"))
        assertFalse("content changes must not invalidate the observation", isStale(token))
    }

    @Test fun touchEventsStillFeedTheSession() {
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, null))
        assertEquals("touch start must pause dispatch", DispatchGate.Paused, session.dispatchGate())
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, null))
        assertEquals("touch end must arm the idle-resume path", DispatchGate.Paused, session.dispatchGate())
    }

    @Test fun nullEventIsIgnored() {
        val token = observedToken()
        service.onAccessibilityEvent(null)
        assertFalse("a null event must not disturb the observation", isStale(token))
    }
}
