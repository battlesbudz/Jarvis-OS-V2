package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import kotlin.math.round

class AndroidMobileActionExecutor(
    private val context: Context,
    private val canLaunchDirectly: () -> Boolean = { false },
    private val onDiagnostic: (String) -> Unit = {}
) : MobileActionExecutor {
    private val appResolver = InstalledAppResolver(context)
    override fun execute(action: MobileAction): ExecutionResult = when (action) {
        MobileAction.ReadBattery -> {
            val batteryManager = context.getSystemService(BatteryManager::class.java)
            val percent = batteryManager?.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
            if (percent == null || percent !in 0..100) {
                ExecutionResult(false, "Battery status is unavailable.")
            } else {
                ExecutionResult.battery(percent)
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
                val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (actual != target) {
                    ExecutionResult(false, "Android did not apply the requested media volume.")
                } else {
                    ExecutionResult(true, "Media volume set to ${action.level} percent.")
                }
            }
        }
        is MobileAction.OpenApp -> when (val resolution = appResolver.resolve(
            action.appName,
            action.packageNameHint
        )) {
            is AppResolution.NotFound ->
                ExecutionResult(false, "I could not find an installed app named ${resolution.requestedName}.")
            is AppResolution.Ambiguous ->
                ExecutionResult(
                    false,
                    "I found multiple apps matching ${resolution.requestedName}: " +
                        resolution.matches.joinToString { it.label } +
                        ". Please specify one."
                )
            is AppResolution.Found -> {
                val launchIntent = Intent().setClassName(
                    resolution.app.packageName,
                    resolution.app.activityName
                )
                val visible = canLaunchDirectly()
                val assistantSelected = com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isSelected(context)
                val assistantResult = if (!visible)
                    com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.launch(context, launchIntent, resolution.app.label)
                else null
                if (assistantResult != null) {
                    onDiagnostic("App launch route=selected_assistant visible=$visible selected=$assistantSelected app=${resolution.app.packageName} result=${assistantResult.succeeded}")
                    assistantResult
                } else try {
                    // Activity visibility alone does not describe Android's launch eligibility.
                    // A recently used activity, system binding, or user-granted exemption may
                    // allow this explicit command. Let Android evaluate the real request.
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    onDiagnostic("App launch route=${if (visible) "visible_activity" else "background_request"} visible=$visible selected=$assistantSelected app=${resolution.app.packageName} result=submitted foregroundTransition=unobserved")
                    // startActivity returns void, and BAL denials may be silent. A background
                    // submission must not be described as a verified foreground transition.
                    ExecutionResult(true, if (visible) "Opening ${resolution.app.label}."
                        else "Requested opening ${resolution.app.label}.")
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: android.content.ActivityNotFoundException) {
                    onDiagnostic("App launch result=rejected type=ActivityNotFoundException app=${resolution.app.packageName}")
                    ExecutionResult(false, "Could not open ${resolution.app.label}: ${error.message ?: "Android rejected the launch."}")
                } catch (error: SecurityException) {
                    onDiagnostic("App launch result=rejected type=SecurityException app=${resolution.app.packageName}")
                    ExecutionResult(false, "Could not open ${resolution.app.label}: ${error.message ?: "Android rejected the launch."}")
                }
            }
        }
    }
}
