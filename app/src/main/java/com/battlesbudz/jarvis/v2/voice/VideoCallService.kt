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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Companion to [VoiceCallService]: holds the camera foreground-service type
 * and runs the frame-capture pipeline while a call is active.
 *
 * Audio stays entirely in VoiceCallService — this service never touches the
 * microphone, TTS, or audio routing. Lifecycle: started when a call starts
 * (see the hook in VoiceCallService.onCreate), stopped by its own Stop
 * action, by [onTaskRemoved], or automatically when the voice call ends
 * (it observes VoiceCallService.stopRequested as a fail-safe).
 *
 * If the camera permission is denied, the service still runs (so Stop works)
 * but never binds the camera: the call degrades to audio-only.
 */
class VideoCallService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: CallVisionController
    private var status = "Starting video"

    override fun onCreate() {
        super.onCreate()
        val hub = VisionFrameHub()
        val cadence = FrameCadence()
        val binder = CameraXVideoBinder(this, this, hub, cadence)
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
        val cameraGranted = CameraPermission.isGranted(this)
        startForeground(
            NOTIFICATION_ID, notification(),
            if (cameraGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        // Fail-safe: a dead voice call must never leave the camera running.
        scope.launch {
            VoiceCallService.stopRequested.collect { stop ->
                if (stop) stopSelf()
            }
        }

        controller.start(cameraGranted)
        if (!cameraGranted) {
            status = "Camera unavailable — grant permission to enable video"
            notifyChanged()
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
        if (intent?.action == STOP) stopSelf()
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

        /** Start the video companion for an active call. No-op if already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoCallService::class.java),
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
