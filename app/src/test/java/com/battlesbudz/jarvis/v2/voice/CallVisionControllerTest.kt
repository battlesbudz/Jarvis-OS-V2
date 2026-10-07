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
        assertEquals(CallVisionController.State.DENIED, controller.start(cameraPermissionGranted = false))
        assertEquals(0, binder.binds)
        assertEquals(0, binder.unbinds)
    }

    @Test fun grantedPermissionBindsCamera() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true))
        assertEquals(1, binder.binds)
    }

    @Test fun doubleStartBindsOnlyOnce() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true)
        controller.start(cameraPermissionGranted = true)
        assertEquals(1, binder.binds)
        assertEquals(CallVisionController.State.ACTIVE, controller.state)
    }

    @Test fun stopUnbindsAndClears() {
        val binder = FakeBinder()
        val hub = VisionFrameHub()
        val controller = CallVisionController(binder, hub = hub)
        controller.start(cameraPermissionGranted = true)
        hub.register(object : VisionObserver {
            override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
        })
        hub.dispatch(byteArrayOf(1), 100L)
        assertNotNull(hub.latest())

        assertEquals(CallVisionController.State.IDLE, controller.stop())
        assertEquals(1, binder.unbinds)
        assertNull(hub.latest())
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
        controller.start(cameraPermissionGranted = false)
        assertEquals(CallVisionController.State.ACTIVE, controller.start(cameraPermissionGranted = true))
        assertEquals(1, binder.binds)
    }

    @Test fun burstOnlyAppliesWhileActive() {
        val binder = FakeBinder()
        val cadence = FrameCadence(framesPerSecond = 1.0, clockMs = { 0L })
        val controller = CallVisionController(binder, cadence = cadence)
        // No crash, no effect while idle.
        controller.requestBurst(5_000L)
        controller.start(cameraPermissionGranted = true)
        controller.requestBurst(5_000L, fps = 10.0)
        assertTrue(cadence.shouldCapture(0L))
        cadence.markCaptured(0L)
        // 10 fps -> next capture at 100ms.
        assertTrue(cadence.shouldCapture(100L))
    }

    @Test fun restartAfterStopRebinds() {
        val binder = FakeBinder()
        val controller = CallVisionController(binder)
        controller.start(cameraPermissionGranted = true)
        controller.stop()
        controller.start(cameraPermissionGranted = true)
        assertEquals(2, binder.binds)
        assertEquals(1, binder.unbinds)
    }
}
