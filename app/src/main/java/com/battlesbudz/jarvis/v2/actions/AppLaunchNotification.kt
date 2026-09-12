package com.battlesbudz.jarvis.v2.actions

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** A notification tap is the Android-supported launch path while Jarvis is backgrounded. */
object AppLaunchNotification {
    fun offer(context: Context, intent: Intent, label: String): ExecutionResult {
        val manager = context.getSystemService(NotificationManager::class.java)
        if ((Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled()) {
            return ExecutionResult(false, "I haven't opened $label. Bring Jarvis to the foreground and ask again, or enable Jarvis notifications for a tap-to-open shortcut.")
        }
        return runCatching {
            val channel = "jarvis_app_launch"
            manager.createNotificationChannel(NotificationChannel(channel, "Requested app launches", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) {
                return ExecutionResult(false, "I haven't opened $label. Enable Jarvis requested app launch notifications, or return to Jarvis and ask again.")
            }
            val pending = PendingIntent.getActivity(context, 482,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(482, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Open $label")
                .setContentText("Tap to open the app you requested.")
                .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(60_000).build())
            ExecutionResult(false, "I haven't opened $label yet. Android requires a tap while Jarvis is in the background. Tap the Open $label notification.")
        }.getOrElse { ExecutionResult(false, "I couldn't offer the $label shortcut. Return to Jarvis and ask again.") }
    }
}
