package com.battlesbudz.jarvis.v2.voice

import androidx.camera.core.ImageProxy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The bind lifecycle with a fake camera platform: cancellation during
 * initialization, provider/camera failures, and executor ownership.
 * No camera hardware; the production [CameraXPlatform] is not exercised.
 */
class CameraXVideoBinderTest {
    private class FakeHandle : CameraXVideoBinder.CameraHandle {
        var detaches = 0
        override fun detach() { detaches++ }
    }

    private class FakeAttachHandle : CameraXVideoBinder.AttachHandle {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }

    /** Captures the attach callback; the test fires it when the scenario demands. */
    private class FakePlatform : CameraXVideoBinder.CameraPlatform {
        var attachCalls = 0
        var pending: ((CameraXVideoBinder.AttachResult) -> Unit)? = null
        val attachHandles = mutableListOf<FakeAttachHandle>()
        var syncResult: CameraXVideoBinder.AttachResult? = null

        override fun attach(
            analyzerExecutor: Executor,
            analyzer: (ImageProxy) -> Unit,
            onResult: (CameraXVideoBinder.AttachResult) -> Unit,
        ): CameraXVideoBinder.AttachHandle {
            attachCalls++
            val handle = FakeAttachHandle()
            attachHandles += handle
            val result = syncResult
            if (result != null) onResult(result) else pending = onResult
            return handle
        }

        fun deliver(result: CameraXVideoBinder.AttachResult) {
            val callback = pending ?: error("no pending attach to deliver")
            pending = null
            callback(result)
        }
    }

    private class TrackingExecutorService(
        private val delegate: ExecutorService = Executors.newSingleThreadExecutor()
    ) : ExecutorService by delegate {
        var shutdownNowCalls = 0
        override fun shutdownNow(): List<Runnable> {
            shutdownNowCalls++
            return delegate.shutdownNow()
        }
    }

    private class Fixture {
        val platform = FakePlatform()
        val errors = mutableListOf<VideoError>()
        val executors = mutableListOf<TrackingExecutorService>()
        val binder = CameraXVideoBinder(
            hub = VisionFrameHub(),
            cadence = FrameCadence(),
            onVideoError = { errors += it },
            platform = platform,
            executorFactory = { TrackingExecutorService().also { executors += it } },
        )
    }

    @Test fun delayedProviderCompletionAfterStopNeverBindsLate() {
        val f = Fixture()
        f.binder.bind()
        assertEquals(1, f.platform.attachCalls)
        f.binder.unbind()
        // The pending attempt was cancelled and its executor released...
        assertTrue(f.platform.attachHandles.single().cancelled)
        assertEquals(1, f.executors.single().shutdownNowCalls)
        // ...so the late provider completion binds nothing and leaks nothing.
        val late = FakeHandle()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(late))
        assertEquals("A late handle must be released, not installed", 1, late.detaches)
        assertTrue("No error for a cleanly cancelled attempt", f.errors.isEmpty())
        // A fresh bind starts a new attempt: nothing was left installed.
        f.binder.bind()
        assertEquals(2, f.platform.attachCalls)
    }

    @Test fun failedProviderReportsErrorAndReleasesExecutor() {
        val f = Fixture()
        f.binder.bind()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Unavailable(VideoError.PROVIDER_FAILED))
        assertEquals(listOf(VideoError.PROVIDER_FAILED), f.errors)
        assertEquals(1, f.executors.single().shutdownNowCalls)
        // The binder is usable again after a failure.
        f.binder.bind()
        assertEquals(2, f.platform.attachCalls)
        assertEquals(2, f.executors.size)
    }

    @Test fun unavailableBackCameraReportsNoBackCamera() {
        val f = Fixture()
        f.binder.bind()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Unavailable(VideoError.NO_BACK_CAMERA))
        assertEquals(listOf(VideoError.NO_BACK_CAMERA), f.errors)
        assertEquals(1, f.executors.single().shutdownNowCalls)
    }

    @Test fun bindFailureReportsBindFailed() {
        val f = Fixture()
        f.binder.bind()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Unavailable(VideoError.BIND_FAILED))
        assertEquals(listOf(VideoError.BIND_FAILED), f.errors)
    }

    @Test fun repeatedStartStopLeavesNoLeak() {
        val f = Fixture()
        val handles = mutableListOf<FakeHandle>()
        repeat(3) {
            f.binder.bind()
            val handle = FakeHandle()
            handles += handle
            f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(handle))
            f.binder.unbind()
        }
        assertEquals(3, f.platform.attachCalls)
        assertEquals(3, f.executors.size)
        f.executors.forEach {
            assertEquals("Every attempt's executor must be shut down", 1, it.shutdownNowCalls)
        }
        handles.forEach {
            assertEquals("Every attached handle must be detached", 1, it.detaches)
        }
        assertTrue(f.errors.isEmpty())
    }

    @Test fun bindWhileAttachPendingIsIdempotent() {
        val f = Fixture()
        f.binder.bind()
        f.binder.bind()
        assertEquals(1, f.platform.attachCalls)
        assertEquals(1, f.executors.size)
    }

    @Test fun bindWhileAttachedIsIdempotent() {
        val f = Fixture()
        f.binder.bind()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(FakeHandle()))
        f.binder.bind()
        assertEquals(1, f.platform.attachCalls)
    }

    @Test fun unbindWhenIdleIsSafe() {
        val f = Fixture()
        f.binder.unbind()
        assertEquals(0, f.platform.attachCalls)
        assertTrue(f.executors.isEmpty())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun unbindDuringPendingAttachCancelsIt() {
        val f = Fixture()
        f.binder.bind()
        f.binder.unbind()
        assertTrue(f.platform.attachHandles.single().cancelled)
        // Late delivery after the cancel installs nothing.
        val late = FakeHandle()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(late))
        assertEquals(1, late.detaches)
        f.binder.bind()
        assertEquals(2, f.platform.attachCalls)
    }

    @Test fun synchronousDeliveryInstallsAndTearsDownCleanly() {
        val f = Fixture()
        val handle = FakeHandle()
        f.platform.syncResult = CameraXVideoBinder.AttachResult.Attached(handle)
        f.binder.bind()
        assertEquals(1, f.platform.attachCalls)
        f.binder.unbind()
        assertEquals(1, handle.detaches)
        assertEquals(1, f.executors.single().shutdownNowCalls)
    }

    @Test fun synchronousFailureReportsError() {
        val f = Fixture()
        f.platform.syncResult =
            CameraXVideoBinder.AttachResult.Unavailable(VideoError.PROVIDER_FAILED)
        f.binder.bind()
        assertEquals(listOf(VideoError.PROVIDER_FAILED), f.errors)
        assertEquals(1, f.executors.single().shutdownNowCalls)
    }
}
