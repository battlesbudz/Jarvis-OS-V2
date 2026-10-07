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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Real [CallVisionController.VideoBinder]: binds CameraX ImageAnalysis,
 * converts YUV frames to small JPEGs behind the cadence gate, and dispatches
 * to the [VisionFrameHub].
 *
 * Battery-conscious by construction: 640x480 analysis resolution,
 * STRATEGY_KEEP_ONLY_LATEST (a slow consumer never queues frames), and the
 * [FrameCadence] gate drops everything above the configured rate before any
 * conversion work happens.
 */
class CameraXVideoBinder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val hub: VisionFrameHub,
    private val cadence: FrameCadence,
) : CallVisionController.VideoBinder {

    @Volatile
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null

    override fun bind() {
        if (provider != null) return
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        ProcessCameraProvider.getInstance(context).addListener({
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(exec) { image -> analyze(image) }
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis,
            )
            analysis = imageAnalysis
            provider = cameraProvider
        }, ContextCompat.getMainExecutor(context))
    }

    override fun unbind() {
        val cameraProvider = provider ?: return
        try {
            analysis?.let { cameraProvider.unbind(it) }
        } catch (_: Exception) {
            // Unbinding must never throw out of lifecycle teardown.
        }
        provider = null
        analysis = null
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
