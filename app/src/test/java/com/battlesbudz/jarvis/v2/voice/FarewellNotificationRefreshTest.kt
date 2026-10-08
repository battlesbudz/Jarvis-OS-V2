package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

/**
 * A spoken farewell ends the call's video capture outside the video
 * service's start/stop command path. [farewellVideoStatus] is the exact
 * composition the runtime runs on a farewell (via
 * VideoCallService.refreshVideoStatusAfterFarewell) before the resulting
 * status is pushed into the service notification: these tests drive that
 * real path and assert the notification status reflects the controller,
 * instead of only exercising [videoStatusText] as a pure helper.
 */
class FarewellNotificationRefreshTest {
    private class FakeBinder : CallVisionController.VideoBinder {
        var binds = 0
        var unbinds = 0
        override var cleanupUnresolved = false
        override fun bind(): CallVisionController.BindResult {
            binds++
            return CallVisionController.BindResult.Started
        }
        override fun unbind() { unbinds++ }
    }

    private fun withRegistry(controller: CallVisionController?, block: () -> Unit) {
        val original = CallVisionRegistry.controller
        CallVisionRegistry.controller = controller
        try {
            block()
        } finally {
            CallVisionRegistry.controller = original
        }
    }

    @Test fun spokenFarewellRefreshesNotificationStatusToIdle() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            // The call's capture is live: the notification reads "Video on".
            assertEquals(CallVisionController.State.ACTIVE,
                controller.start(cameraPermissionGranted = true, callId = "call-1"))
            assertEquals("Video on", videoStatusText(controller.state))

            // The real farewell-to-notification path: end the capture and
            // derive the status the service notification must display.
            val status = farewellVideoStatus("call-1")

            assertEquals("A farewell must unbind the camera", 1, binder.unbinds)
            assertEquals(CallVisionController.State.IDLE, controller.state)
            assertEquals(
                "The notification must reflect the IDLE controller, never the stale \"Video on\"",
                "Video idle — starts with your next call", status)
        }
    }

    @Test fun staleFarewellKeepsActiveNotificationStatus() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        withRegistry(controller) {
            controller.start(cameraPermissionGranted = true, callId = "call-2")
            // A stale farewell for an older call must not touch the newer
            // call's capture — the notification keeps reading "Video on".
            val status = farewellVideoStatus("call-1")
            assertEquals(CallVisionController.State.ACTIVE, controller.state)
            assertEquals("A stale farewell must not unbind the camera", 0, binder.unbinds)
            assertEquals("Video on", status)
        }
    }

    @Test fun farewellWithNoRegisteredControllerReportsIdle() {
        withRegistry(null) {
            assertEquals("Video idle — starts with your next call",
                farewellVideoStatus("call-1"))
        }
    }
}
