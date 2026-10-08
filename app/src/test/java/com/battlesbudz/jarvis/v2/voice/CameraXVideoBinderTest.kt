package com.battlesbudz.jarvis.v2.voice

import androidx.camera.core.ImageProxy
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The bind lifecycle with a fake camera platform: cancellation during
 * initialization, provider/camera failures, and executor ownership.
 * No camera hardware; the production [CameraXPlatform] is not exercised.
 */

class CameraXVideoBinderTest {
    private class FakeHandle : CameraXVideoBinder.CameraHandle {
        var detaches = 0
        override fun detach(): CameraXVideoBinder.DetachOutcome {
            detaches++
            return CameraXVideoBinder.DetachOutcome.Detached
        }
    }

    private class FakeAttachHandle : CameraXVideoBinder.AttachHandle {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }

    /** A handle whose detach outcome is scripted per call, for retry scenarios. */
    private class ScriptedHandle(
        vararg outcomes: CameraXVideoBinder.DetachOutcome
    ) : CameraXVideoBinder.CameraHandle {
        var detaches = 0
        private val queue = ArrayDeque(outcomes.toList())
        override fun detach(): CameraXVideoBinder.DetachOutcome {
            detaches++
            return queue.removeFirstOrNull() ?: CameraXVideoBinder.DetachOutcome.Detached
        }
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
        val detachOutcomes = mutableListOf<CameraXVideoBinder.DetachOutcome>()
        val executors = mutableListOf<TrackingExecutorService>()
        val binder = CameraXVideoBinder(
            hub = VisionFrameHub(),
            cadence = FrameCadence(),
            onVideoError = { errors += it },
            platform = platform,
            executorFactory = { TrackingExecutorService().also { executors += it } },
            onDetachOutcome = { detachOutcomes += it },
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

    @Test fun spokenFarewellUnbindsOldUseCaseOnMainThreadAndNextCallStartsClean() {
        // The spoken-farewell path: STOP_CAPTURE -> stopForCall(callId) ->
        // binder.unbind() -> CameraHandle.detach(). The detach must marshal
        // the CameraX unbind to the main thread and retain ownership until
        // it completes; the next call then starts a fresh capture under its
        // own identity (the transition into passive wake listening leaves
        // the controller IDLE with no capture owner).
        val main = FakeMainExecutor()
        try {
            val unbindThreads = Collections.synchronizedList(mutableListOf<Thread>())
            val f = Fixture()
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder { unbindThreads += Thread.currentThread() },
                mainExecutor = main,
                // The farewell is delivered off the main thread here;
                // production may call from onStartCommand (main) or a
                // service thread — both must land the unbind on main.
                isMainThread = { false },
            )
            f.platform.syncResult = CameraXVideoBinder.AttachResult.Attached(handle)
            val controller = CallVisionController(f.binder)
            assertEquals(CallVisionController.State.ACTIVE, controller.start(true, "call-1"))
            // Spoken farewell for the owning call ends the capture.
            assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-1"))
            assertNull("farewell clears the capture owner", controller.captureCallId())
            assertEquals("the old use case must be unbound", 1, unbindThreads.size)
            assertEquals("the unbind must run on the main thread",
                main.mainThread, unbindThreads.single())
            assertEquals("a clean detach reports its completion",
                listOf(CameraXVideoBinder.DetachOutcome.Detached), f.detachOutcomes)
            // A new call starts a fresh capture under its own identity; the
            // old call's farewell is now stale and must be a no-op.
            assertEquals(CallVisionController.State.ACTIVE, controller.start(true, "call-2"))
            assertEquals("call-2", controller.captureCallId())
            controller.stopForCall("call-1")
            assertEquals("a stale farewell must not end the new call's capture",
                CallVisionController.State.ACTIVE, controller.state)
            assertEquals("a stale farewell must not unbind again",
                1, unbindThreads.size)
            controller.stop()
        } finally {
            main.shutdown()
        }
    }

    @Test fun detachFailureRetainsOwnershipAndBlocksCaptureUntilCleanupConfirmed() {
        // Jerry's review (build 1196): a failed detach must surface through
        // the teardown outcome — never be silently treated as successful
        // cleanup — and ownership must be retained: the old use case may
        // still be bound, so a fresh capture cannot start on top of it.
        // Only a confirmed detach releases ownership and re-arms capture.
        val f = Fixture()
        f.binder.bind()
        val boom = RuntimeException("unbind blew up")
        val handle = ScriptedHandle(
            CameraXVideoBinder.DetachOutcome.Failed(boom),
            CameraXVideoBinder.DetachOutcome.Detached, // the retry confirms cleanup
        )
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(handle))
        f.binder.unbind()
        assertEquals("a failed detach must surface, not vanish",
            listOf(CameraXVideoBinder.DetachOutcome.Failed(boom)), f.detachOutcomes)
        assertTrue("failed cleanup must be an explicit state, not silent",
            f.binder.cleanupUnresolved)
        // A fresh capture is blocked while cleanup is unresolved: no new
        // attempt may install while the binder still owns the handle.
        f.binder.bind()
        assertEquals("no new capture while cleanup is unresolved", 1, f.platform.attachCalls)
        assertTrue("still unresolved after the blocked bind", f.binder.cleanupUnresolved)
        // The next unbind retries the retained handle's detach. Once the
        // detach is confirmed, ownership releases and a fresh capture works.
        f.binder.unbind()
        assertEquals(2, handle.detaches)
        assertFalse("confirmed cleanup must clear the unresolved state",
            f.binder.cleanupUnresolved)
        assertEquals(
            listOf(
                CameraXVideoBinder.DetachOutcome.Failed(boom),
                CameraXVideoBinder.DetachOutcome.Detached,
            ),
            f.detachOutcomes,
        )
        f.binder.bind()
        assertEquals(2, f.platform.attachCalls)
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(FakeHandle()))
        f.binder.unbind()
        assertFalse(f.binder.cleanupUnresolved)
    }

    @Test fun detachTimeoutRetainsOwnershipAndBlocksCaptureUntilCleanupConfirmed() {
        // A timed-out detach reports TimedOut, retains ownership, and blocks
        // a fresh capture; a later confirmed detach re-arms capture.
        val f = Fixture()
        f.binder.bind()
        val handle = ScriptedHandle(
            CameraXVideoBinder.DetachOutcome.TimedOut,
            CameraXVideoBinder.DetachOutcome.Detached,
        )
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(handle))
        f.binder.unbind()
        assertEquals("a timed-out detach must surface, not vanish",
            listOf(CameraXVideoBinder.DetachOutcome.TimedOut), f.detachOutcomes)
        assertTrue("timed-out cleanup must be an explicit state, not silent",
            f.binder.cleanupUnresolved)
        f.binder.bind()
        assertEquals("no new capture while cleanup is unresolved", 1, f.platform.attachCalls)
        // Retry confirms the detach; capture is allowed again.
        f.binder.unbind()
        assertEquals(2, handle.detaches)
        assertFalse(f.binder.cleanupUnresolved)
        f.binder.bind()
        assertEquals(2, f.platform.attachCalls)
    }

    @Test fun detachTimeoutSurfacesAndDoesNotWedgeTeardown() {
        // A detach that never completes within its bound reports TimedOut;
        // teardown itself returns instead of wedging.
        val f = Fixture()
        f.binder.bind()
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(
            object : CameraXVideoBinder.CameraHandle {
                override fun detach(): CameraXVideoBinder.DetachOutcome {
                    Thread.sleep(400) // a wedged platform detach; the handle bounds it
                    return CameraXVideoBinder.DetachOutcome.TimedOut
                }
            }))
        val start = System.currentTimeMillis()
        f.binder.unbind()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("unbind must return promptly, took ${elapsed}ms", elapsed < 5_000L)
        assertEquals("a wedged detach must surface as a timeout, not silent success",
            listOf(CameraXVideoBinder.DetachOutcome.TimedOut), f.detachOutcomes)
    }

    @Test fun unbindRetainsOwnershipUntilDetachCompletes() {
        // A still-pending detach must not be treated as successful cleanup:
        // unbind keeps the handle installed until detach returns, and a bind
        // racing the pending teardown installs nothing.
        val f = Fixture()
        f.binder.bind()
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(
            object : CameraXVideoBinder.CameraHandle {
                override fun detach(): CameraXVideoBinder.DetachOutcome {
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    events += "detached"
                    return CameraXVideoBinder.DetachOutcome.Detached
                }
            }))
        val t = Thread {
            f.binder.unbind()
            events += "unbind-returned"
        }
        t.start()
        Thread.sleep(300)
        assertTrue("teardown must retain ownership while detach is pending, got $events",
            events.isEmpty())
        f.binder.bind()
        assertEquals("no new attempt may install while teardown owns the handle",
            1, f.platform.attachCalls)
        release.countDown()
        t.join(5_000L)
        assertEquals(listOf("detached", "unbind-returned"), events)
        assertEquals(listOf(CameraXVideoBinder.DetachOutcome.Detached), f.detachOutcomes)
    }

    @Test fun failedCleanupToNextCallRefusesBindKeepsPendingVisible() {
        // Jerry's camera-integration finding, end to end across all three
        // layers: a failed detach followed by the next call must refuse the
        // bind, keep the pending cleanup visible in the controller state,
        // and never let the service status advertise video that the binder
        // deliberately did not start. The retained handle and the
        // call-identity fences are preserved throughout.
        val f = Fixture()
        val controller = CallVisionController(f.binder)
        assertEquals(CallVisionController.State.ACTIVE, controller.start(true, "call-1"))
        val boom = RuntimeException("unbind blew up")
        f.platform.deliver(CameraXVideoBinder.AttachResult.Attached(ScriptedHandle(
            CameraXVideoBinder.DetachOutcome.Failed(boom), // call-1's teardown
            CameraXVideoBinder.DetachOutcome.Failed(boom), // call-2's cleanup retry
            CameraXVideoBinder.DetachOutcome.Detached,     // call-2's farewell confirms
        )))
        // Call 1 ends: the detach fails, so the controller must NOT report
        // IDLE — the unresolved cleanup stays visible.
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.stopForCall("call-1"))
        assertEquals("the owning call identity is retained through the pending cleanup",
            "call-1", controller.captureCallId())
        assertTrue(f.binder.cleanupUnresolved)
        // Binder layer: the next bind is refused explicitly, not silently.
        assertEquals(CallVisionController.BindResult.CleanupBlocked, f.binder.bind())
        assertEquals("no fresh capture may install while cleanup is unresolved",
            1, f.platform.attachCalls)
        // Controller layer: the next call retries the retained detach, the
        // retry fails, and the controller stays pending under the new call's
        // identity — never ACTIVE.
        assertEquals(CallVisionController.State.CLEANUP_PENDING, controller.start(true, "call-2"))
        assertEquals("call-2", controller.captureCallId())
        assertEquals(1, f.platform.attachCalls)
        assertTrue(f.binder.cleanupUnresolved)
        // Service layer: the displayed status never advertises video for a
        // refused bind.
        val status = videoStatusText(controller.state)
        assertNotEquals("Video on", status)
        assertTrue("the pending status must name the cleanup, got: $status",
            status.contains("cleanup", ignoreCase = true))
        // The waiting call's farewell retries the retained detach; the
        // confirmed cleanup releases the block and returns to IDLE.
        assertEquals(CallVisionController.State.IDLE, controller.stopForCall("call-2"))
        assertFalse(f.binder.cleanupUnresolved)
        assertNull(controller.captureCallId())
        // A later call starts a genuinely fresh capture.
        assertEquals(CallVisionController.State.ACTIVE, controller.start(true, "call-3"))
        assertEquals(2, f.platform.attachCalls)
        assertEquals("call-3", controller.captureCallId())
        controller.stop()
        assertEquals(CallVisionController.State.IDLE, controller.state)
        assertFalse(f.binder.cleanupUnresolved)
    }
}

/** A "main executor" with its own real thread, like Android's main looper thread. */
private class FakeMainExecutor : Executor {
    private val queue = LinkedBlockingQueue<Runnable>()
    val mainThread: Thread = Thread({
        try {
            while (true) queue.take().run()
        } catch (_: InterruptedException) {
            // shut down
        }
    }, "fake-main-thread").also { it.start() }
    val runThreads = Collections.synchronizedList(mutableListOf<Thread>())

    override fun execute(command: Runnable) {
        queue.put(Runnable {
            runThreads += Thread.currentThread()
            command.run()
        })
    }

    fun shutdown() {
        mainThread.interrupt()
        mainThread.join(2_000L)
    }
}

/**
 * Real-thread teardown for the production CameraX detach path
 * ([MainThreadCameraHandle]): the unbind must run on the main executor
 * (CameraX requires the main thread), detach must not return before the
 * outcome is known, and a cleanup failure or timeout is reported as the
 * returned [CameraXVideoBinder.DetachOutcome] instead of being silently swallowed. No camera
 * hardware.
 */
class MainThreadCameraHandleTest {
    @Test fun detachFromWorkerThreadUnbindsOnMainExecutor() {
        val main = FakeMainExecutor()
        try {
            val unbindThreads = Collections.synchronizedList(mutableListOf<Thread>())
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder { unbindThreads += Thread.currentThread() },
                mainExecutor = main,
                isMainThread = { false },
            )
            // Detach runs on a worker thread (not the main thread).
            val caller = Thread.currentThread()
            assertEquals(CameraXVideoBinder.DetachOutcome.Detached, handle.detach())
            assertEquals("the old use case must be unbound", 1, unbindThreads.size)
            assertEquals("the unbind must run on the main thread, not the caller",
                main.mainThread, unbindThreads.single())
            assertTrue("the caller thread must not perform the unbind",
                unbindThreads.single() != caller)
            assertEquals(listOf(main.mainThread), main.runThreads)
        } finally {
            main.shutdown()
        }
    }

    @Test fun detachDoesNotReturnBeforeUnbindCompletes() {
        val main = FakeMainExecutor()
        try {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val release = CountDownLatch(1)
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder {
                    // A slow main-thread unbind: detach must wait for this.
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    events += "unbound"
                },
                mainExecutor = main,
                isMainThread = { false },
                detachTimeoutMs = 5_000L,
            )
            val t = Thread {
                handle.detach()
                events += "detach-returned"
            }
            t.start()
            // Give detach() every chance to (incorrectly) return early: the
            // unbind is still blocked, so a correct detach cannot have
            // returned yet.
            Thread.sleep(300)
            assertTrue("detach must retain ownership until the unbind completes, got $events",
                events.isEmpty())
            release.countDown()
            t.join(5_000L)
            assertEquals(listOf("unbound", "detach-returned"), events)
        } finally {
            main.shutdown()
        }
    }

    @Test fun detachOnMainThreadUnbindsDirectlyWithoutPosting() {
        val main = FakeMainExecutor()
        try {
            val unbindThreads = Collections.synchronizedList(mutableListOf<Thread>())
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder { unbindThreads += Thread.currentThread() },
                mainExecutor = main,
                // Already on the main thread: unbind directly (posting would
                // deadlock the bounded wait).
                isMainThread = { true },
            )
            val caller = Thread.currentThread()
            assertEquals(CameraXVideoBinder.DetachOutcome.Detached, handle.detach())
            assertEquals(listOf(caller), unbindThreads)
            assertTrue("nothing may be posted when already on main", main.runThreads.isEmpty())
        } finally {
            main.shutdown()
        }
    }

    @Test fun detachFailureIsReportedNotSwallowed() {
        val main = FakeMainExecutor()
        try {
            val boom = RuntimeException("unbind blew up")
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder { throw boom },
                mainExecutor = main,
                isMainThread = { false },
            )
            // Detach never throws out of teardown...
            val outcome = handle.detach()
            // ...but the cleanup failure must surface, not vanish.
            assertTrue("a cleanup failure must be reported, not silently swallowed, got $outcome",
                outcome is CameraXVideoBinder.DetachOutcome.Failed && outcome.cause === boom)
        } finally {
            main.shutdown()
        }
    }

    @Test fun detachTimeoutIsReportedNotSilent() {
        val main = FakeMainExecutor()
        try {
            val release = CountDownLatch(1)
            val handle = MainThreadCameraHandle(
                unbinder = CameraXUnbinder {
                    try {
                        // A wedged main thread: the unbind never completes.
                        release.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                mainExecutor = main,
                isMainThread = { false },
                detachTimeoutMs = 300L,
            )
            assertEquals("a wedged detach must report a timeout, not silent success",
                CameraXVideoBinder.DetachOutcome.TimedOut, handle.detach()) // returns after the bounded wait
            release.countDown()
        } finally {
            main.shutdown()
        }
    }

    @Test fun rejectedMainExecutorTaskIsReportedNotSilent() {
        val handle = MainThreadCameraHandle(
            unbinder = CameraXUnbinder { fail("the unbind must never run") },
            mainExecutor = Executor { throw java.util.concurrent.RejectedExecutionException("shut down") },
            isMainThread = { false },
        )
        val outcome = handle.detach()
        assertTrue("a rejected detach must be reported, not silently swallowed, got $outcome",
            outcome is CameraXVideoBinder.DetachOutcome.Failed &&
                outcome.cause is java.util.concurrent.RejectedExecutionException)
    }
}
