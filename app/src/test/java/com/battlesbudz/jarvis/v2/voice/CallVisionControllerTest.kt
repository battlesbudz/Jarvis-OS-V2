package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class CallVisionControllerTest {
    private class FakeBinder : CallVisionController.VideoBinder {
        var binds = 0
        var unbinds = 0
        override fun bind() { binds++ }
        override fun unbind() { unbinds++ }
    }

    @Test fun deniedPermissionNeverTouchesBinder() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.DENIED, controller.start(cameraPermissionGranted = false, callId = "call-1"))
        assertEquals(0, binder.binds)
        assertEquals(0, binder.unbinds)
    }

    @Test fun grantedPermissionBindsCamera() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true, callId = "call-1"))
        assertEquals(1, binder.binds)
        assertEquals("call-1", controller.captureCallId())
    }

    @Test fun doubleStartBindsOnlyOnce() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        assertEquals(1, binder.binds)
        assertEquals(CallVisionController.State.ACTIVE, controller.state)
    }

    @Test fun stopUnbindsAndClears() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertEquals(1, binder.unbinds)
        assertNull(hub.latest())
        assertNull(controller.captureCallId())
    }

    @Test fun stopWhenIdleIsSafe() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertEquals(0, binder.unbinds)
    }

    @Test fun deniedCanRecoverAfterGrant() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = false, callId = "call-1")
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true, callId = "call-1"))
        assertEquals(1, binder.binds)
    }

    @Test fun burstOnlyAppliesWhileActive() {
        val binder = FakeBinder()
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = { 0L })
        val controller = CallVisionController(binder, cadence = cadence)
        // No crash, no effect while idle.
        controller.requestBurst(5_000L)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.requestBurst(5_000L, fps = 10.0)
        assertTrue(cadence.shouldCapture(0L))
        cadence.markCaptured(0L)
        // 10 fps -> next capture at 100ms.
        assertTrue(cadence.shouldCapture(100L))
    }

    @Test fun restartAfterStopRebinds() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        controller.stop()
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals(1, binder.unbinds)
    }

    // -- Call-identity gating: only the owning call controls its capture. ---

    @Test fun stopForCallWithOwningIdentityStopsCapture() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
        assertEquals(1, binder.unbinds)
        assertNull("The frame cache must be cleared with the capture", hub.latest())
        assertNull(controller.captureCallId())
    }

    @Test fun stopForCallWithStaleIdentityIsNoOp() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // A stale farewell for an older call must not touch the active capture.
        assertEquals(CallVisionController.State.ACTIVE, controller.stopForCall("call-0"))
        assertEquals(0, binder.unbinds)
        assertEquals("call-1", controller.captureCallId())
    }

    @Test fun stopForCallWhenIdleIsSafe() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
        assertEquals(0, binder.unbinds)
    }

    @Test fun newCallRebindsAfterStaleStop() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // The farewell for call-1 ends its capture...
        controller.stopForCall("call-1")
        assertEquals(1, binder.unbinds)
        // ...and the next call starts a fresh capture under its own identity.
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals("call-2", controller.captureCallId())
        // A late farewell for the old call cannot end the new capture.
        controller.stopForCall("call-1")
        assertEquals(CallVisionController.State.ACTIVE, controller.state)
        assertEquals(1, binder.unbinds)
        assertEquals("call-2", controller.captureCallId())
    }

    @Test fun differentCallRebindsDefensively() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true, callId = "call-1")
        // beginCall guarantees no overlap, but a late start for another call
        // must never inherit the dead call's capture.
        controller.start(cameraPermissionGranted = true, callId = "call-2")
        assertEquals(2, binder.binds)
        assertEquals(1, binder.unbinds)
        assertEquals("call-2", controller.captureCallId())
    }

    @Test fun blankCallIdIsRejected() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        try {
            controller.start(cameraPermissionGranted = true, callId = "")
            fail("Capture without a call identity must be rejected.")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, binder.binds)
    }
}
