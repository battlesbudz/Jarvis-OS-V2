package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import kotlin.math.round

class AndroidMobileActionExecutor(
    private val context: Context
) : MobileActionExecutor {
    override fun execute(action: MobileAction): ExecutionResult = when (action) {
        MobileAction.ReadBattery -> {
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val percent = batteryManager?.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
            if (percent == null || percent < 0) {
                ExecutionResult(false, "Battery status is unavailable.")
            } else {
                ExecutionResult(true, "Battery is at $percent percent.")
            }
        }
        is MobileAction.SetVolume -> {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (max == 0) {
                ExecutionResult(false, "Media volume is unavailable.")
            } else {
                val target = round(max * action.level / 100.0).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                ExecutionResult(true, "Media volume set to ${action.level} percent.")
            }
        }
        is MobileAction.OpenApp -> {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(action.packageName)
                ?: return ExecutionResult(false, "The app ${action.packageName} is not installed or has no launcher activity.")
            runCatching {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }.fold(
                { ExecutionResult(true, "Opened ${action.packageName}.") },
                { error ->
                    ExecutionResult(false, "Could not open ${action.packageName}: ${error.message ?: "Android rejected the launch."}")
                }
            )
        }
    }
}