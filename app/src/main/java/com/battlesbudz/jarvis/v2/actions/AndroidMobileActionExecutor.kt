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
    private val onDiagnostic: (String) -> Unit = {},
    private val screenBridge: ScreenBridge = ScreenControlService.bridge(context),
    val screenSession: ScreenControlSession = ScreenControlService.sharedSession,
    /**
     * Scheduling bridge behind create_reminder/show_schedule. Defaults to the
     * context itself when it implements [ReminderScheduling] (JarvisRuntime
     * does), so production call sites need no changes; journeys pass an
     * explicit coordinator over a scratch store.
     */
    reminderScheduling: ReminderScheduling? = null
) : MobileActionExecutor {
    private val scheduling: ReminderScheduling? = reminderScheduling ?: (context as? ReminderScheduling)
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
                } else {
                    // Background activity starts are silently dropped by Android 10+
                    // background activity-start (BAL) restrictions without an exemption.
                    // The selected-assistant route above is one exemption;
                    // "Display over other apps" (SYSTEM_ALERT_WINDOW, declared in the
                    // manifest) is another. With neither, a raw background
                    // startActivity can never arrive — report the block honestly
                    // instead of the old optimistic "Requested opening X" success.
                    val overlayExempt = android.provider.Settings.canDrawOverlays(context)
                    if (!visible && !overlayExempt) {
                        onDiagnostic("App launch result=blocked_background visible=false selected=$assistantSelected app=${resolution.app.packageName}")
                        ExecutionResult(
                            false,
                            "I couldn't open ${resolution.app.label} while another app is in front — " +
                                "Android blocked the background launch. Set Jarvis as your default assistant " +
                                "or grant \"Display over other apps\" in Settings, then ask again."
                        )
                    } else submitLaunch(
                        launchIntent,
                        resolution.app.label,
                        route = if (visible) "visible_activity" else "overlay_exempt",
                        assistantSelected = assistantSelected
                    )
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
            openedText = "Opening"
        )
        is MobileAction.OpenSettings -> dispatchViewIntent(
            Intent(action.screen.intentAction),
            label = "${action.screen.label} settings",
            openedText = "Opening"
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
            openedText = "Showing directions to"
        )
        MobileAction.ScreenObserve -> observeScreen()
        is MobileAction.ScreenTap -> dispatchScreenMutation(
            verb = "tap",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.clickable) "Target ${node.id} (\"${node.label}\") is not tappable. " +
                    "Pick a button from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.tap(node) },
            successText = { node -> "Tapped \"${node.label}\"." }
        )
        is MobileAction.ScreenScroll -> dispatchScreenMutation(
            verb = "scroll ${action.direction.key}",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.scrollable) "Target ${node.id} (\"${node.label}\") is not scrollable. " +
                    "Pick a list from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.scroll(node, action.direction) },
            successText = { node -> "Scrolled \"${node.label}\" ${action.direction.key}." }
        )
        is MobileAction.ScreenType -> dispatchScreenMutation(
            verb = "type into",
            targetId = action.targetId,
            token = action.token,
            requireNode = { node ->
                if (!node.editable) "Target ${node.id} (\"${node.label}\") is not an editable field. " +
                    "Pick a field from the latest screen_observe result." else null
            },
            perform = { bridge, node -> bridge.type(node, action.text) },
            successText = { node -> "Typed into \"${node.label}\"." }
        )
        is MobileAction.CreateReminder -> {
            val bridge = scheduling
            if (bridge == null) {
                onDiagnostic("create_reminder result=unavailable reason=no_scheduling_bridge")
                ExecutionResult(false, "Reminders are unavailable right now, so nothing was scheduled.")
            } else {
                val result = bridge.createReminder(action.message, action.atMs)
                onDiagnostic("create_reminder result=${result.succeeded} message=${result.message.take(80)}")
                result
            }
        }
        is MobileAction.ShowSchedule -> {
            val bridge = scheduling
            if (bridge == null) {
                onDiagnostic("show_schedule result=unavailable reason=no_scheduling_bridge")
                ExecutionResult(false, "I couldn't read the schedule right now.")
            } else {
                bridge.describeSchedule()
            }
        }
        is MobileAction.PostNotification -> {
            val posted = ReminderNotification.post(context, action.title, action.text)
            onDiagnostic("post_notification result=$posted title=${action.title.take(40)}")
            if (posted) ExecutionResult(true, "Posted the reminder notification.")
            else ExecutionResult(false,
                "I couldn't post the reminder notification: notifications are disabled or permission was denied.")
        }
    }

    /**
     * Fire a NEW_TASK launch and report the platform's verdict. Callers must
     * only reach this when the launch is eligible: the activity is visible,
     * the selected-assistant route handled it, or a BAL exemption (overlay
     * grant) applies. startActivity returns void, so only the caught
     * rejections are reported as failures; silent background drops are kept
     * out by the eligibility check at the call sites.
     */
    private fun submitLaunch(
        intent: Intent,
        label: String,
        route: String,
        assistantSelected: Boolean
    ): ExecutionResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        onDiagnostic("App launch route=$route selected=$assistantSelected label=$label result=submitted")
        ExecutionResult(true, "Opening $label.")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: android.content.ActivityNotFoundException) {
        onDiagnostic("App launch result=rejected type=ActivityNotFoundException label=$label")
        ExecutionResult(false, "Could not open $label: ${error.message ?: "Android rejected the launch."}")
    } catch (error: SecurityException) {
        onDiagnostic("App launch result=rejected type=SecurityException label=$label")
        ExecutionResult(false, "Could not open $label: ${error.message ?: "Android rejected the launch."}")
    }

    /**
     * Dispatch a view intent through the same launch path as OpenApp: the
     * assistant service when the activity is not visible, otherwise a direct
     * startActivity. A background launch with no BAL exemption is reported as
     * blocked, never as an optimistic "requested" success.
     */
    private fun dispatchViewIntent(
        intent: Intent,
        label: String,
        openedText: String
    ): ExecutionResult {
        val visible = canLaunchDirectly()
        val assistantSelected = com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isSelected(context)
        val assistantResult = if (!visible)
            com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.launch(context, intent, label)
        else null
        if (assistantResult != null) {
            onDiagnostic("View intent route=selected_assistant visible=$visible label=$label result=${assistantResult.succeeded}")
            return assistantResult
        }
        val overlayExempt = android.provider.Settings.canDrawOverlays(context)
        if (!visible && !overlayExempt) {
            onDiagnostic("View intent result=blocked_background visible=false label=$label")
            return ExecutionResult(
                false,
                "I couldn't open $label while another app is in front — " +
                    "Android blocked the background launch. Set Jarvis as your default assistant " +
                    "or grant \"Display over other apps\" in Settings, then ask again."
            )
        }
        return submitLaunch(
            intent,
            label,
            route = if (visible) "visible_activity" else "overlay_exempt",
            assistantSelected = assistantSelected
        ).let { result ->
            // Preserve the caller's wording for the verified launch; the
            // "requested" wording no longer occurs because unverified
            // background submissions are blocked above.
            if (result.succeeded) ExecutionResult(true, "$openedText $label.") else result
        }
    }

    /**
     * M1c screen observation. Read-only: needs no session grant, but every
     * observation rotates the token that later mutations must present.
     */
    private fun observeScreen(): ExecutionResult {
        if (!screenBridge.isAvailable()) {
            onDiagnostic("screen_observe result=unavailable")
            return ExecutionResult(
                false,
                "Screen observation is not available. Enable Jarvis screen control " +
                    "in Android Accessibility settings, then try again."
            )
        }
        val observation = screenBridge.observe()
        if (observation == null) {
            onDiagnostic("screen_observe result=empty")
            return ExecutionResult(false, "I could not read the current screen. Nothing was tapped or typed.")
        }
        val token = screenSession.recordObservation(observation)
        onDiagnostic("screen_observe result=observed nodes=${observation.nodes.size} pkg=${observation.packageName}")
        return ExecutionResult(true, observation.compactText(token))
    }

    /**
     * M1c screen mutation with verified targets. The session gate enforces the
     * grant (D23), user-touch pause with re-observe resume (T06), and the stop
     * request; [ScreenControlSession.verifyTarget] rejects stale tokens/targets
     * and the bridge re-verifies the live node before dispatching.
     */
    private fun dispatchScreenMutation(
        verb: String,
        targetId: String,
        token: String,
        requireNode: (ScreenNode) -> String?,
        perform: (ScreenBridge, ScreenNode) -> Boolean,
        successText: (ScreenNode) -> String
    ): ExecutionResult {
        when (screenSession.dispatchGate()) {
            DispatchGate.Stopped ->
                return ExecutionResult(false, "The screen task was stopped.")
            DispatchGate.NeedsAdmission ->
                return ExecutionResult(
                    false,
                    "Screen control needs your approval for this task before I can $verb. " +
                        "Approve the screen task first."
                )
            DispatchGate.Paused ->
                return ExecutionResult(
                    false,
                    "Paused while you are touching the screen. I will look at the screen " +
                        "again and resume when you stop."
                )
            DispatchGate.ResumeReobserve -> {
                // Idle resume re-observes the changed screen without a countdown
                // (T06). The token rotates, so the caller's token is now stale by
                // design and the mutation below is rejected as stale.
                val fresh = screenBridge.observe()
                if (fresh == null) {
                    return ExecutionResult(false, "I could not read the current screen after you stopped touching it.")
                }
                screenSession.recordObservation(fresh)
                onDiagnostic("screen_$verb result=reobserved nodes=${fresh.nodes.size} pkg=${fresh.packageName}")
            }
            DispatchGate.Allowed -> Unit
        }
        val liveWindowIdentity = screenBridge.currentWindowIdentity()
        val node = when (val verified = screenSession.verifyTarget(targetId, token, liveWindowIdentity, requireNode)) {
            is TargetVerification.Verified -> verified.node
            is TargetVerification.Rejected -> {
                onDiagnostic("screen_$verb result=rejected reason=${verified.reason.take(80)}")
                return ExecutionResult(false, verified.reason)
            }
        }
        onDiagnostic("screen_$verb target=$targetId label=\"${node.label}\"")
        val completed = try {
            perform(screenBridge, node)
        } catch (_: SecurityException) {
            return ExecutionResult(
                ExecutionResult.Outcome.DENIED_PERMISSION,
                "Android denied the screen $verb."
            )
        }
        // performAction returns whether Android performed the action, so the
        // receipt reports the real outcome instead of claiming success.
        return if (completed) ExecutionResult(true, successText(node))
        else ExecutionResult(
            false,
            "I could not $verb \"${node.label}\" — the screen may have changed. " +
                "Call screen_observe again for fresh targets."
        )
    }
}
