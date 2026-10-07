package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Why video capture degraded to audio-only. */
enum class VideoError { PROVIDER_FAILED, NO_BACK_CAMERA, BIND_FAILED }

/**
 * Real [CallVisionController.VideoBinder]: binds CameraX ImageAnalysis,
 * converts YUV frames to small JPEGs behind the cadence gate, and dispatches
 * to the [VisionFrameHub].
 *
 * Battery-conscious by construction: 640x480 analysis resolution,
 * STRATEGY_KEEP_ONLY_LATEST (a slow consumer never queues frames), and the
 * [FrameCadence] gate drops everything above the configured rate before any
 * conversion work happens.
 *
 * Cancellation safety: [bind] is asynchronous (the provider initializes off
 * the calling thread). Every bind attempt owns an [AttachHandle]; [unbind]
 * cancels a still-pending attempt so a stop during initialization can never
 * bind after teardown, and always shuts down the analyzer executor — even
 * when the provider never arrived — so no executor leaks. Provider or
 * camera failures never escape: they route to [onVideoError] and the call
 * continues audio-only.
 */
class CameraXVideoBinder(
    private val hub: VisionFrameHub,
    private val cadence: FrameCadence,
    private val onVideoError: (VideoError) -> Unit = {},
    private val platform: CameraPlatform,
    private val executorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() },
) : CallVisionController.VideoBinder {

    /** Production wiring: the real CameraX platform. Tests use the primary constructor with a fake. */
    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        hub: VisionFrameHub,
        cadence: FrameCadence,
        onVideoError: (VideoError) -> Unit = {},
    ) : this(hub, cadence, onVideoError, CameraXPlatform(context, lifecycleOwner))

    /**
     * The camera-platform surface the binder drives. Production implements
     * it with CameraX ([CameraXPlatform]); tests substitute a fake. Narrow
     * by design so the bind lifecycle — cancellation, failure, degradation —
     * is unit-testable with no camera hardware.
     */
    interface CameraPlatform {
        /**
         * Attach camera analysis. [onResult] fires exactly once: [AttachResult.Attached]
         * with a live handle, or [AttachResult.Unavailable] when the camera
         * cannot be used. The returned handle cancels a still-pending attach;
         * a cancelled attach never delivers.
         */
        fun attach(
            analyzerExecutor: Executor,
            analyzer: (ImageProxy) -> Unit,
            onResult: (AttachResult) -> Unit,
        ): AttachHandle
    }

    sealed interface AttachResult {
        data class Attached(val handle: CameraHandle) : AttachResult
        data class Unavailable(val error: VideoError) : AttachResult
    }

    interface CameraHandle {
        /** Best-effort detach; never throws. */
        fun detach()
    }

    interface AttachHandle {
        /** Invalidate a still-pending attach; safe to call after delivery. */
        fun cancel()
    }

    private val lock = Any()
    private var binding = false
    private var generation = 0L
    private var attachHandle: AttachHandle? = null
    private var cameraHandle: CameraHandle? = null
    private var executor: ExecutorService? = null

    override fun bind() {
        val attempt: Long
        val exec: ExecutorService
        synchronized(lock) {
            if (binding || cameraHandle != null) return
            binding = true
            generation++
            attempt = generation
            exec = executorFactory()
            executor = exec
        }
        val handle = platform.attach(exec, ::analyze) { result ->
            var error: VideoError? = null
            var lateHandle: CameraHandle? = null
            synchronized(lock) {
                if (attempt != generation) {
                    // Superseded: unbind ran during initialization (or a newer
                    // bind replaced this attempt). A stop during init must
                    // never bind after teardown — release what was built and
                    // install nothing.
                    lateHandle = (result as? AttachResult.Attached)?.handle
                } else {
                    binding = false
                    attachHandle = null
                    when (result) {
                        is AttachResult.Attached -> cameraHandle = result.handle
                        is AttachResult.Unavailable -> {
                            error = result.error
                            shutdownExecutorLocked()
                        }
                    }
                }
            }
            lateHandle?.detach()
            error?.let { onVideoError(it) }
        }
        synchronized(lock) {
            // The platform may deliver synchronously; only retain the handle
            // while this attempt is still the live one.
            if (attempt == generation) attachHandle = handle else handle.cancel()
        }
    }

    override fun unbind() {
        val toCancel: AttachHandle?
        val toDetach: CameraHandle?
        val exec: ExecutorService?
        synchronized(lock) {
            // Invalidate any pending attempt first: its late callback can no
            // longer install a binding after this teardown.
            generation++
            toCancel = attachHandle
            attachHandle = null
            toDetach = cameraHandle
            cameraHandle = null
            exec = executor
            executor = null
            binding = false
        }
        // Cancel first: a pending provider callback can no longer bind after
        // this teardown. Then detach, then always shut the executor down —
        // even when the provider never arrived — so no thread leaks.
        toCancel?.cancel()
        toDetach?.detach()
        exec?.shutdownNow()
    }

    private fun shutdownExecutorLocked() {
        executor?.shutdownNow()
        executor = null
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (!cadence.shouldCapture(now)) return
            val jpeg = yuvToJpeg(image) ?: return
            if (jpeg.size > MAX_JPEG_BYTES) return // fail closed on absurd frames
            cadence.markCaptured(now)
            hub.dispatch(jpeg, now)
        } finally {
            image.close()
        }
    }

    /**
     * YUV_420_888 -> NV21 -> JPEG. Copies rows respecting each plane's
     * rowStride/pixelStride, so it works on devices with padded planes.
     */
    private fun yuvToJpeg(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        if (image.width <= 0 || image.height <= 0) return null
        val width = image.width
        val height = image.height
        return try {
            val nv21 = ByteArray(width * height * 3 / 2)
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            var pos = 0
            for (row in 0 until height) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(nv21, pos, width)
                pos += width
            }
            val vBuf = vPlane.buffer
            val uBuf = uPlane.buffer
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    // NV21 order is VU interleaved.
                    nv21[pos++] = vBuf.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    nv21[pos++] = uBuf.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            if (yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)) {
                out.toByteArray()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val JPEG_QUALITY = 70
        private const val MAX_JPEG_BYTES = 1024 * 1024
    }
}

/**
 * [CameraXVideoBinder.CameraPlatform] over the real CameraX provider.
 * Provider initialization, the back-camera check, and binding all run off
 * the caller's thread; the result posts on the main executor. Failures
 * become [VideoError] — never an escaped exception.
 */
private class CameraXPlatform(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraXVideoBinder.CameraPlatform {
    override fun attach(
        analyzerExecutor: Executor,
        analyzer: (ImageProxy) -> Unit,
        onResult: (CameraXVideoBinder.AttachResult) -> Unit,
    ): CameraXVideoBinder.AttachHandle {
        val cancelled = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (cancelled.get()) return@addListener
            val result: CameraXVideoBinder.AttachResult = try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraXVideoBinder.AttachResult.Unavailable(VideoError.NO_BACK_CAMERA)
                } else {
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { image -> analyzer(image) }
                    val bound = try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            analysis,
                        )
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (!bound) {
                        CameraXVideoBinder.AttachResult.Unavailable(VideoError.BIND_FAILED)
                    } else {
                        CameraXVideoBinder.AttachResult.Attached(
                            object : CameraXVideoBinder.CameraHandle {
                                override fun detach() {
                                    try {
                                        provider.unbind(analysis)
                                    } catch (_: Exception) {
                                        // Detach must never throw out of teardown.
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (_: Exception) {
                CameraXVideoBinder.AttachResult.Unavailable(VideoError.PROVIDER_FAILED)
            }
            deliverIfLive(cancelled, result, onResult)
        }, ContextCompat.getMainExecutor(context))
        return object : CameraXVideoBinder.AttachHandle {
            override fun cancel() { cancelled.set(true) }
        }
    }

    private fun deliverIfLive(
        cancelled: AtomicBoolean,
        result: CameraXVideoBinder.AttachResult,
        onResult: (CameraXVideoBinder.AttachResult) -> Unit,
    ) {
        if (cancelled.get()) {
            // A stop during initialization: release what we just built and
            // never deliver — no late binding after teardown.
            (result as? CameraXVideoBinder.AttachResult.Attached)?.handle?.detach()
            return
        }
        onResult(result)
    }
}
