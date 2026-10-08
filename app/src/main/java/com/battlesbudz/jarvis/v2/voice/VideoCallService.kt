package com.battlesbudz.jarvis.v2.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.battlesbudz.jarvis.v2.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Companion to [VoiceCallService]: holds the camera foreground-service type
 * and runs the frame-capture pipeline while a call is active.
 *
 * Audio stays entirely in VoiceCallService — this service never touches the
 * microphone, TTS, or audio routing. Lifecycle: capture starts per call via
 * [startCapture] (the call's identity owns the capture) and ends when that
 * call ends, by its own Stop action, by [onTaskRemoved], or automatically
 * when the voice session ends (it observes VoiceCallService.stopRequested
 * as a fail-safe). Capture never runs for a merely-armed wake session: a
 * spoken farewell ends the call's capture while wake listening stays armed.
 *
 * If the camera permission is denied, the service still runs (so Stop works)
 * but never binds the camera: the call degrades to audio-only.
 */
class VideoCallService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: CallVisionController
    private var status = "Video idle — starts with your next call"

    override fun onCreate() {
        super.onCreate()
        val hub = VisionFrameHub()
        val cadence = FrameCadence()
        val binder = CameraXVideoBinder(this, this, hub, cadence, onVideoError = { error ->
            controller.degrade(error)
            status = "Camera unavailable — continuing audio-only"
            notifyChanged()
        }, onDetachFailure = { cause ->
            // A failed or timed-out camera detach must never be silently
            // treated as successful cleanup: log it and say so on the
            // service status so a wedged capture is visible.
            android.util.Log.e("JarvisVideo", "Camera detach failed during teardown", cause)
            status = "Camera cleanup failed — video may misbehave until the next call"
            notifyChanged()
        })
        controller = CallVisionController(binder, cadence, hub)
        CallVisionRegistry.hub = hub
        CallVisionRegistry.controller = controller

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Video", NotificationManager.IMPORTANCE_LOW)
        )
        // The camera FGS type requires the CAMERA runtime permission on API 34+
        // (targetSdk 35): starting it without the grant throws SecurityException
        // out of onCreate and kills the whole process. When denied, fall back to
        // the specialUse type declared in the manifest, so the service still runs
        // (Stop keeps working) and the call degrades to audio-only.
        //
        // The grant check can also lie: Android documents that background
        // camera-FGS creation may still throw SecurityException even when the
        // check reports granted. Catch that too and degrade instead of dying.
        val cameraGranted = CameraPermission.isGranted(this)
        startForegroundSafely(
            if (cameraGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        // Fail-safe: a dead voice call must never leave the camera running.
        scope.launch {
            VoiceCallService.stopRequested.collect { stop ->
                if (stop) stopSelf()
            }
        }
        // Capture is not started here: it begins per call via START_CAPTURE,
        // so a merely-armed wake session never holds the camera.
    }

    /**
     * True once a camera-type foreground start was rejected (background
     * start restriction): later capture starts degrade to audio-only
     * instead of retrying a start the platform will refuse.
     */
    @Volatile
    private var cameraForegroundRejected = false

    private fun startForegroundSafely(type: Int) {
        try {
            startForeground(NOTIFICATION_ID, notification(), type)
        } catch (_: SecurityException) {
            cameraForegroundRejected = true
            startForeground(
                NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VideoCallService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis video")
            .setContentText(status)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop video", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyChanged() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopSelf()
            START_CAPTURE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!callId.isNullOrBlank()) {
                    // A rejected camera foreground start degrades like a
                    // denied permission: the call continues audio-only.
                    val granted = CameraPermission.isGranted(this) && !cameraForegroundRejected
                    controller.start(granted, callId)
                    // The status is derived from the controller state, so a
                    // refused bind (cleanup pending) can never read "Video on".
                    status = videoStatusText(controller.state)
                    notifyChanged()
                }
            }
            STOP_CAPTURE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!callId.isNullOrBlank()) {
                    // Only the owning call's identity stops the capture; a
                    // stale stop for an older call is a no-op. The status is
                    // derived from the controller state, so an unresolved
                    // cleanup stays visible instead of reading idle.
                    controller.stopForCall(callId)
                    status = videoStatusText(controller.state)
                    notifyChanged()
                }
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        try {
            controller.stop()
        } catch (_: Exception) {
            // Teardown must never throw.
        }
        CallVisionRegistry.clear()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_video_calls"
        private const val NOTIFICATION_ID = 482
        private const val STOP = "com.battlesbudz.jarvis.v2.STOP_VIDEO"
        private const val START_CAPTURE = "com.battlesbudz.jarvis.v2.START_VIDEO_CAPTURE"
        private const val STOP_CAPTURE = "com.battlesbudz.jarvis.v2.STOP_VIDEO_CAPTURE"
        private const val EXTRA_CALL_ID = "com.battlesbudz.jarvis.v2.EXTRA_CALL_ID"

        /**
         * Begin call-scoped video capture for [callId]. Starts the service
         * when needed; only [callId] owns the capture, so ending any other
         * call cannot stop it.
         */
        fun startCapture(context: Context, callId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoCallService::class.java)
                    .setAction(START_CAPTURE)
                    .putExtra(EXTRA_CALL_ID, callId),
            )
        }
    }
}

/**
 * Process-wide access to the active call's vision pipeline. Slice 2 (and any
 * UI) registers a [VisionObserver] here; null when no video call is active.
 */
object CallVisionRegistry {
    @Volatile
    var hub: VisionFrameHub? = null
        internal set

    @Volatile
    var controller: CallVisionController? = null
        internal set

    internal fun clear() {
        hub = null
        controller = null
    }
}
