package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.view.KeyEvent
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
        is MobileAction.MediaControl -> {
            val audioManager = context.getSystemService(AudioManager::class.java)
            if (audioManager == null) {
                ExecutionResult(false, "Media control is unavailable.")
            } else {
                val keyCode = when (action.action) {
                    MediaControlAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                    MediaControlAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    MediaControlAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    MediaControlAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    MediaControlAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val eventTime = SystemClock.uptimeMillis()
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                )
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                )
                // The key event is dispatched to the active media session; Android does
                // not report whether a session consumed it, so the receipt describes the
                // dispatch honestly rather than claiming a playback state change.
                ExecutionResult(
                    true,
                    "Sent ${action.action.label} command to the active media session."
                )
            }
        }
        is MobileAction.OpenWebsite -> dispatchViewIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(action.url)),
            label = action.url,
            openedText = "Opening",
            requestedText = "Requested opening"
        )
        is MobileAction.OpenSettings -> dispatchViewIntent(
            Intent(action.screen.intentAction),
            label = "${action.screen.label} settings",
            openedText = "Opening",
            requestedText = "Requested opening"
        )
        is MobileAction.Navigate -> dispatchViewIntent(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=" +
                        Uri.encode(action.destination)
                )
            ),
            label = action.destination,
            openedText = "Showing directions to",
            requestedText = "Requested directions to"
        )
    }

    /**
     * Dispatch a view intent through the same launch path as OpenApp: the
     * assistant service when the activity is not visible, otherwise a direct
     * startActivity. startActivity returns void and background denials may be
     * silent, so a background submission is reported as requested, not verified.
     */
    private fun dispatchViewIntent(
        intent: Intent,
        label: String,
        openedText: String,
        requestedText: String
    ): ExecutionResult {
        val visible = canLaunchDirectly()
        val assistantResult = if (!visible)
            com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.launch(context, intent, label)
        else null
        if (assistantResult != null) {
            onDiagnostic("View intent route=selected_assistant visible=$visible label=$label result=${assistantResult.succeeded}")
            return assistantResult
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            onDiagnostic("View intent route=${if (visible) "visible_activity" else "background_request"} visible=$visible label=$label result=submitted foregroundTransition=unobserved")
            ExecutionResult(true, if (visible) "$openedText $label." else "$requestedText $label.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: android.content.ActivityNotFoundException) {
            onDiagnostic("View intent result=rejected type=ActivityNotFoundException label=$label")
            ExecutionResult(false, "Could not open $label: ${error.message ?: "Android rejected the launch."}")
        } catch (error: SecurityException) {
            onDiagnostic("View intent result=rejected type=SecurityException label=$label")
            ExecutionResult(false, "Could not open $label: ${error.message ?: "Android rejected the launch."}")
        }
    }
}
